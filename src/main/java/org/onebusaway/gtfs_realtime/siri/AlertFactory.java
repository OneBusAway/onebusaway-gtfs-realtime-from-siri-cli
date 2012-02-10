/**
 * Copyright (C) 2011 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onebusaway.gtfs_realtime.siri;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.onebusaway.collections.CollectionsLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.org.siri.siri.AffectedCallStructure;
import uk.org.siri.siri.AffectedOperatorStructure;
import uk.org.siri.siri.AffectedStopPointStructure;
import uk.org.siri.siri.AffectedVehicleJourneyStructure;
import uk.org.siri.siri.AffectedVehicleJourneyStructure.Calls;
import uk.org.siri.siri.AffectsScopeStructure;
import uk.org.siri.siri.AffectsScopeStructure.Operators;
import uk.org.siri.siri.AffectsScopeStructure.StopPoints;
import uk.org.siri.siri.AffectsScopeStructure.VehicleJourneys;
import uk.org.siri.siri.DefaultedTextStructure;
import uk.org.siri.siri.HalfOpenTimestampRangeStructure;
import uk.org.siri.siri.OperatorRefStructure;
import uk.org.siri.siri.PtConsequenceStructure;
import uk.org.siri.siri.PtConsequencesStructure;
import uk.org.siri.siri.PtSituationElementStructure;
import uk.org.siri.siri.ServiceConditionEnumeration;
import uk.org.siri.siri.StopPointRefStructure;
import uk.org.siri.siri.VehicleJourneyRefStructure;

import com.google.transit.realtime.GtfsRealtime.Alert;
import com.google.transit.realtime.GtfsRealtime.Alert.Cause;
import com.google.transit.realtime.GtfsRealtime.Alert.Effect;
import com.google.transit.realtime.GtfsRealtime.EntitySelector;
import com.google.transit.realtime.GtfsRealtime.TimeRange;
import com.google.transit.realtime.GtfsRealtime.TranslatedString;
import com.google.transit.realtime.GtfsRealtime.TranslatedString.Translation;
import com.google.transit.realtime.GtfsRealtime.TripDescriptor;

@Singleton
public class AlertFactory {

  private static final Logger _log = LoggerFactory.getLogger(AlertFactory.class);

  private IdService _idService;

  @Inject
  public void setIdService(IdService idService) {
    _idService = idService;
  }

  public Alert.Builder createAlertFromSituation(
      PtSituationElementStructure ptSituation) {

    Alert.Builder alert = Alert.newBuilder();

    handleDescriptions(ptSituation, alert);
    handleOtherFields(ptSituation, alert);
    handlReasons(ptSituation, alert);
    handleAffects(ptSituation, alert);
    handleConsequences(ptSituation, alert);

    return alert;
  }

  private void handleDescriptions(PtSituationElementStructure ptSituation,
      Alert.Builder serviceAlert) {

    TranslatedString summary = translation(ptSituation.getSummary());
    if (summary != null)
      serviceAlert.setHeaderText(summary);

    TranslatedString description = translation(ptSituation.getDescription());
    if (description != null)
      serviceAlert.setDescriptionText(description);
  }

  private void handleOtherFields(PtSituationElementStructure ptSituation,
      Alert.Builder serviceAlert) {

    if (ptSituation.getPublicationWindow() != null) {
      HalfOpenTimestampRangeStructure window = ptSituation.getPublicationWindow();
      TimeRange.Builder range = TimeRange.newBuilder();
      if (window.getStartTime() != null)
        range.setStart(window.getStartTime().getTime());
      if (window.getEndTime() != null)
        range.setEnd(window.getEndTime().getTime());
      if (range.hasStart() || range.hasEnd())
        serviceAlert.addActivePeriod(range);
    }
  }

  private void handlReasons(PtSituationElementStructure ptSituation,
      Alert.Builder serviceAlert) {

    Cause cause = getReasonAsCause(ptSituation);
    if (cause != null)
      serviceAlert.setCause(cause);
  }

  private Cause getReasonAsCause(PtSituationElementStructure ptSituation) {
    if (ptSituation.getEnvironmentReason() != null)
      return Cause.WEATHER;
    if (ptSituation.getEquipmentReason() != null) {
      switch (ptSituation.getEquipmentReason()) {
        case CONSTRUCTION_WORK:
          return Cause.CONSTRUCTION;
        case CLOSED_FOR_MAINTENANCE:
        case MAINTENANCE_WORK:
        case EMERGENCY_ENGINEERING_WORK:
        case LATE_FINISH_TO_ENGINEERING_WORK:
        case REPAIR_WORK:
          return Cause.MAINTENANCE;
        default:
          return Cause.TECHNICAL_PROBLEM;
      }
    }
    if (ptSituation.getPersonnelReason() != null) {
      switch (ptSituation.getPersonnelReason()) {
        case INDUSTRIAL_ACTION:
        case UNOFFICIAL_INDUSTRIAL_ACTION:
          return Cause.STRIKE;
      }
      return Cause.OTHER_CAUSE;
    }
    /**
     * There are really so many possibilities here that it's tricky to translate
     * them all
     */
    if (ptSituation.getMiscellaneousReason() != null) {
      switch (ptSituation.getMiscellaneousReason()) {
        case ACCIDENT:
        case COLLISION:
          return Cause.ACCIDENT;
        case DEMONSTRATION:
        case MARCH:
          return Cause.DEMONSTRATION;
        case PERSON_ILL_ON_VEHICLE:
        case FATALITY:
          return Cause.MEDICAL_EMERGENCY;
        case POLICE_REQUEST:
        case BOMB_ALERT:
        case CIVIL_EMERGENCY:
        case EMERGENCY_SERVICES:
        case EMERGENCY_SERVICES_CALL:
          return Cause.POLICE_ACTIVITY;
      }
    }

    return null;
  }

  /****
   * Affects
   ****/

  private void handleAffects(PtSituationElementStructure ptSituation,
      Alert.Builder serviceAlert) {

    AffectsScopeStructure affectsStructure = ptSituation.getAffects();

    if (affectsStructure == null)
      return;

    Operators operators = affectsStructure.getOperators();

    if (operators != null
        && !CollectionsLibrary.isEmpty(operators.getAffectedOperator())) {

      for (AffectedOperatorStructure operator : operators.getAffectedOperator()) {
        OperatorRefStructure operatorRef = operator.getOperatorRef();
        if (operatorRef == null || operatorRef.getValue() == null)
          continue;
        String agencyId = _idService.id(operatorRef.getValue());
        EntitySelector.Builder selector = EntitySelector.newBuilder();
        selector.setAgencyId(agencyId);
        serviceAlert.addInformedEntity(selector);
      }
    }

    StopPoints stopPoints = affectsStructure.getStopPoints();

    if (stopPoints != null
        && !CollectionsLibrary.isEmpty(stopPoints.getAffectedStopPoint())) {

      for (AffectedStopPointStructure stopPoint : stopPoints.getAffectedStopPoint()) {
        StopPointRefStructure stopRef = stopPoint.getStopPointRef();
        if (stopRef == null || stopRef.getValue() == null)
          continue;
        String stopId = _idService.id(stopRef.getValue());
        EntitySelector.Builder selector = EntitySelector.newBuilder();
        selector.setStopId(stopId);
        serviceAlert.addInformedEntity(selector);
      }
    }

    VehicleJourneys vjs = affectsStructure.getVehicleJourneys();
    if (vjs != null
        && !CollectionsLibrary.isEmpty(vjs.getAffectedVehicleJourney())) {

      for (AffectedVehicleJourneyStructure vj : vjs.getAffectedVehicleJourney()) {

        EntitySelector.Builder selector = EntitySelector.newBuilder();

        if (vj.getLineRef() != null) {
          String routeId = _idService.id(vj.getLineRef().getValue());
          selector.setRouteId(routeId);
        }

        List<VehicleJourneyRefStructure> tripRefs = vj.getVehicleJourneyRef();
        Calls stopRefs = vj.getCalls();

        boolean hasTripRefs = !CollectionsLibrary.isEmpty(tripRefs);
        boolean hasStopRefs = stopRefs != null
            && !CollectionsLibrary.isEmpty(stopRefs.getCall());

        if (!(hasTripRefs || hasStopRefs)) {
          if (selector.hasRouteId())
            serviceAlert.addInformedEntity(selector);
        } else if (hasTripRefs && hasStopRefs) {
          for (VehicleJourneyRefStructure vjRef : vj.getVehicleJourneyRef()) {
            String tripId = _idService.id(vjRef.getValue());
            TripDescriptor.Builder tripDescriptor = TripDescriptor.newBuilder();
            tripDescriptor.setTripId(tripId);
            selector.setTrip(tripDescriptor);
            for (AffectedCallStructure call : stopRefs.getCall()) {
              String stopId = _idService.id(call.getStopPointRef().getValue());
              selector.setStopId(stopId);
              serviceAlert.addInformedEntity(selector);
            }
          }
        } else if (hasTripRefs) {
          for (VehicleJourneyRefStructure vjRef : vj.getVehicleJourneyRef()) {
            String tripId = _idService.id(vjRef.getValue());
            TripDescriptor.Builder tripDescriptor = TripDescriptor.newBuilder();
            tripDescriptor.setTripId(tripId);
            selector.setTrip(tripDescriptor);
            serviceAlert.addInformedEntity(selector);
          }
        } else {
          for (AffectedCallStructure call : stopRefs.getCall()) {
            String stopId = _idService.id(call.getStopPointRef().getValue());
            selector.setStopId(stopId);
            serviceAlert.addInformedEntity(selector);
          }
        }
      }
    }
  }

  private void handleConsequences(PtSituationElementStructure ptSituation,
      Alert.Builder serviceAlert) {

    PtConsequencesStructure consequences = ptSituation.getConsequences();

    if (consequences == null || consequences.getConsequence() == null)
      return;

    for (PtConsequenceStructure consequence : consequences.getConsequence()) {
      if (consequence.getCondition() != null)
        serviceAlert.setEffect(getConditionAsEffect(consequence.getCondition()));
    }
  }

  private Effect getConditionAsEffect(ServiceConditionEnumeration condition) {
    switch (condition) {

      case CANCELLED:
      case NO_SERVICE:
        return Effect.NO_SERVICE;

      case DELAYED:
        return Effect.SIGNIFICANT_DELAYS;

      case DIVERTED:
        return Effect.DETOUR;

      case ADDITIONAL_SERVICE:
      case EXTENDED_SERVICE:
      case SHUTTLE_SERVICE:
      case SPECIAL_SERVICE:
      case REPLACEMENT_SERVICE:
        return Effect.ADDITIONAL_SERVICE;

      case DISRUPTED:
      case INTERMITTENT_SERVICE:
      case SHORT_FORMED_SERVICE:
        return Effect.REDUCED_SERVICE;

      case ALTERED:
      case ARRIVES_EARLY:
      case REPLACEMENT_TRANSPORT:
      case SPLITTING_TRAIN:
        return Effect.MODIFIED_SERVICE;

      case ON_TIME:
      case FULL_LENGTH_SERVICE:
      case NORMAL_SERVICE:
        return Effect.OTHER_EFFECT;

      case UNDEFINED_SERVICE_INFORMATION:
      case UNKNOWN:
        return Effect.UNKNOWN_EFFECT;

      default:
        _log.warn("unknown condition: " + condition);
        return Effect.UNKNOWN_EFFECT;
    }
  }

  private TranslatedString translation(DefaultedTextStructure text) {
    if (text == null)
      return null;
    String value = text.getValue();
    if (value == null)
      return null;

    Translation.Builder translation = Translation.newBuilder();
    translation.setText(value);
    if (text.getLang() != null)
      translation.setLanguage(text.getLang());

    TranslatedString.Builder tsBuilder = TranslatedString.newBuilder();
    tsBuilder.addTranslation(translation);
    return tsBuilder.build();
  }
}
