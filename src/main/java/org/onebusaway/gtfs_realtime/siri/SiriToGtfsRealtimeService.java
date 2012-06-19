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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.xml.datatype.Duration;

import org.onebusaway.siri.core.SiriChannelInfo;
import org.onebusaway.siri.core.SiriClient;
import org.onebusaway.siri.core.SiriClientRequest;
import org.onebusaway.siri.core.SiriLibrary;
import org.onebusaway.siri.core.exceptions.SiriMissingArgumentException;
import org.onebusaway.siri.core.handlers.SiriServiceDeliveryHandler;
import org.onebusaway.siri.core.services.StatusProviderService;
import org.onebusway.gtfs_realtime.exporter.GtfsRealtimeLibrary;
import org.onebusway.gtfs_realtime.exporter.GtfsRealtimeMutableProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.org.siri.siri.EntryQualifierStructure;
import uk.org.siri.siri.FramedVehicleJourneyRefStructure;
import uk.org.siri.siri.LocationStructure;
import uk.org.siri.siri.MonitoredCallStructure;
import uk.org.siri.siri.PtSituationElementStructure;
import uk.org.siri.siri.ServiceDelivery;
import uk.org.siri.siri.SituationExchangeDeliveryStructure;
import uk.org.siri.siri.SituationExchangeDeliveryStructure.Situations;
import uk.org.siri.siri.StopPointRefStructure;
import uk.org.siri.siri.VehicleActivityStructure;
import uk.org.siri.siri.VehicleActivityStructure.MonitoredVehicleJourney;
import uk.org.siri.siri.VehicleMonitoringDeliveryStructure;
import uk.org.siri.siri.VehicleRefStructure;

import com.google.inject.name.Named;
import com.google.transit.realtime.GtfsRealtime.Alert;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.Position;
import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import com.google.transit.realtime.GtfsRealtime.TripUpdate;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeEvent;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate;
import com.google.transit.realtime.GtfsRealtime.VehicleDescriptor;
import com.google.transit.realtime.GtfsRealtime.VehiclePosition;
import com.google.transit.realtime.GtfsRealtimeOneBusAway;
import com.google.transit.realtime.GtfsRealtimeOneBusAway.OneBusAwayFeedEntity;
import com.google.transit.realtime.GtfsRealtimeOneBusAway.OneBusAwayTripUpdate;

@Singleton
public class SiriToGtfsRealtimeService implements StatusProviderService {

  public static final String MONITORING_ERROR_OFF_ROUTE = "OFF_ROUTE";

  public static final String MONITORING_ERROR_NO_CURRENT_INFORMATION = "NO_CURRENT_INFORMATION";

  private static Logger _log = LoggerFactory.getLogger(SiriToGtfsRealtimeService.class);

  private SiriClient _client;

  private AlertFactory _alertFactory;

  private IdService _idService;

  private ScheduledExecutorService _executor;

  private ServiceDeliveryHandlerImpl _serviceDeliveryHandler = new ServiceDeliveryHandlerImpl();

  private BlockingQueue<Delivery> _deliveries = new LinkedBlockingQueue<Delivery>();

  private Map<TripAndVehicleKey, VehicleData> _dataByVehicle = new HashMap<TripAndVehicleKey, VehicleData>();

  private Map<String, AlertData> _alertDataById = new HashMap<String, AlertData>();

  private List<SiriClientRequest> _clientRequests = new ArrayList<SiriClientRequest>();

  /**
   * How often we update the output files, in seconds
   */
  private int _updateFrequency = 10;

  /**
   * Time, in seconds, after which a vehicle update is considered stale
   */
  private int _staleDataThreshold = 5 * 60;

  private Map<String, Integer> _producerPriorities;

  /**
   * When true, the GTFS realtime message are rebuilt on each new
   * ServiceDelivery
   */
  private boolean _rebuildOnEachDelivery = false;

  private Set<String> _monitoringErrorsForTripUpdates = new HashSet<String>() {
    private static final long serialVersionUID = 1L;
    {
      add(MONITORING_ERROR_OFF_ROUTE);
      add(MONITORING_ERROR_NO_CURRENT_INFORMATION);
    }
  };

  private Set<String> _monitoringErrorsForVehiclePositions = new HashSet<String>() {
    private static final long serialVersionUID = 1L;
    {
      add(MONITORING_ERROR_NO_CURRENT_INFORMATION);
    }
  };

  /**
   * The count of how many trip updates have been excluded due to monitoring
   * errors.
   */
  private volatile int _tripUpdateMonitoringErrorCount = 0;

  /**
   * The count of how many vehicle positions have been excluded due to
   * monitoring errors.
   */
  private volatile int _vehiclePositionMonitoringErrorCount = 0;

  private volatile int _tripUpdateCount = 0;

  private volatile int _vehiclePositionCount = 0;

  private GtfsRealtimeMutableProvider _gtfsRealtimeProvider;

  @Inject
  public void setClient(SiriClient client) {
    _client = client;
  }

  @Inject
  public void setGtfsRealtimeProvider(
      GtfsRealtimeMutableProvider gtfsRealtimeProvider) {
    _gtfsRealtimeProvider = gtfsRealtimeProvider;
  }

  @Inject
  public void setAlertFactory(AlertFactory alertFactory) {
    _alertFactory = alertFactory;
  }

  @Inject
  public void setIdService(IdService idService) {
    _idService = idService;
  }

  @Inject
  public void setScheduledExecutorService(
      @Named("SiriToGtfsRealtimeService") ScheduledExecutorService executor) {
    _executor = executor;
  }

  public void addClientRequest(SiriClientRequest request) {
    _clientRequests.add(request);
  }

  /**
   * @param updateFrequency how often we update the output files, in seconds
   */
  public void setUpdateFrequency(int updateFrequency) {
    _updateFrequency = updateFrequency;
  }

  /**
   * @param staleDataThreshold time, in seconds, after which a vehicle update is
   *          considered stale
   */
  public void setStaleDataThreshold(int staleDataThreshold) {
    _staleDataThreshold = staleDataThreshold;
  }

  public void setProducerPriorities(Map<String, Integer> producerPriorities) {
    _log.debug("producer priorities: {}", producerPriorities);
    _producerPriorities = producerPriorities;
  }

  /**
   * 
   * @param rebuildOnEachDelivery when true, the GTFS realtime message are
   *          rebuilt on each new ServiceDelivery
   */
  public void setRebuildOnEachDelivery(boolean rebuildOnEachDelivery) {
    _rebuildOnEachDelivery = rebuildOnEachDelivery;
  }

  /**
   * If a {@link MonitoredVehicleJourney} is not monitored, as determined by
   * {@link MonitoredVehicleJourney#isMonitored()}, and has a
   * {@link MonitoredVehicleJourney#getMonitoringError()} value that is included
   * in the specified monitoring error set, then a {@link TripUpdate} will not
   * be included for the monitored vehicle.
   * 
   * @param monitoringErrors
   */
  public void setMonitoringErrorsForTripUpdates(Set<String> monitoringErrors) {
    _monitoringErrorsForTripUpdates = monitoringErrors;
  }

  /**
   * If a {@link MonitoredVehicleJourney} is not monitored, as determined by
   * {@link MonitoredVehicleJourney#isMonitored()}, and has a
   * {@link MonitoredVehicleJourney#getMonitoringError()} value that is included
   * in the specified monitoring error set, then a {@link VehiclePosition} will
   * not be included for the monitored vehicle.
   * 
   * @param monitoringErrors
   */
  public void setMonitoringErrorsForVehiclePositions(
      Set<String> monitoringErrors) {
    _monitoringErrorsForVehiclePositions = monitoringErrors;
  }

  @PostConstruct
  public void start() throws Exception {

    if (!_rebuildOnEachDelivery) {
      _executor.scheduleAtFixedRate(new SiriToGtfsRealtimeQueueProcessor(), 0,
          _updateFrequency, TimeUnit.SECONDS);
    }

    _client.addServiceDeliveryHandler(_serviceDeliveryHandler);

    for (SiriClientRequest request : _clientRequests)
      _client.handleRequest(request);
  }

  @PreDestroy
  public void stop() throws Exception {
    _client.removeServiceDeliveryHandler(_serviceDeliveryHandler);
    _executor.shutdownNow();
  }

  /***
   * {@link StatusProviderService} Interface
   ****/

  @Override
  public void getStatus(Map<String, String> status) {
    String prefix = "siriToGtfsRealtime.";
    status.put(prefix + "tripUpdateMonitoringErrorCount",
        Integer.toString(_tripUpdateMonitoringErrorCount));
    status.put(prefix + "vehiclePositionMonitoringErrorCount",
        Integer.toString(_vehiclePositionMonitoringErrorCount));
    status.put(prefix + "tripUpdateCount", Integer.toString(_tripUpdateCount));
    status.put(prefix + "vehiclePositionCount",
        Integer.toString(_vehiclePositionCount));
  }

  public void processDeliveries(List<Delivery> deliveries) throws IOException {

    for (Delivery delivery : deliveries) {
      ServiceDelivery serviceDelivery = delivery.serviceDelivery;
      for (VehicleMonitoringDeliveryStructure vmDelivery : serviceDelivery.getVehicleMonitoringDelivery()) {
        for (VehicleActivityStructure vehicleActivity : vmDelivery.getVehicleActivity()) {

          try {
            processVehicleActivity(serviceDelivery, vehicleActivity);
          } catch (SiriMissingArgumentException ex) {
            /**
             * Maybe we should just let the exception kill the process? If
             * you've got a malformed SIRI feed, how much can we do?
             */
            _log.warn(ex.getMessage());
            continue;
          }
        }
      }
      for (SituationExchangeDeliveryStructure sxDelivery : serviceDelivery.getSituationExchangeDelivery()) {
        Situations situations = sxDelivery.getSituations();
        if (situations == null) {
          continue;
        }
        for (PtSituationElementStructure situation : situations.getPtSituationElement()) {
          processSituation(delivery.channelInfo, serviceDelivery, situation);
        }
      }
    }

    writeOutput();
  }

  /****
   * Private Methods
   ****/

  private synchronized void processQueue() throws IOException {

    List<Delivery> deliveries = new ArrayList<Delivery>();
    _deliveries.drainTo(deliveries);
    processDeliveries(deliveries);
  }

  private void processVehicleActivity(ServiceDelivery delivery,
      VehicleActivityStructure vehicleActivity)
      throws SiriMissingArgumentException {

    checkVehicleActivityStructureForMissingElements(vehicleActivity);

    TripAndVehicleKey key = getKeyForVehicleActivity(vehicleActivity);

    String producer = null;
    if (delivery.getProducerRef() != null)
      producer = delivery.getProducerRef().getValue();

    if (isNewDataProducerOfLowerPriorityThanExistingDataProducer(key, producer)) {
      return;
    }

    VehicleData data = new VehicleData(key, System.currentTimeMillis(),
        vehicleActivity, producer);
    _dataByVehicle.put(key, data);
  }

  private void checkVehicleActivityStructureForMissingElements(
      VehicleActivityStructure vehicleActivity) {

    MonitoredVehicleJourney mvj = vehicleActivity.getMonitoredVehicleJourney();
    if (mvj == null)
      throw new SiriMissingArgumentException("MonitoredVehicleJourney",
          "VehicleActivity");

    FramedVehicleJourneyRefStructure fvjRef = mvj.getFramedVehicleJourneyRef();
    if (fvjRef == null)
      throw new SiriMissingArgumentException("FramedVehicleJourneyRef",
          "MonitoredVehicleJourney");

    if (fvjRef.getDataFrameRef() == null
        || fvjRef.getDataFrameRef().getValue() == null)
      throw new SiriMissingArgumentException("DataFrameRef",
          "FramedVehicleJourneyRef");

    if (fvjRef.getDatedVehicleJourneyRef() == null)
      throw new SiriMissingArgumentException("DatedVehicleJourneyRef",
          "FramedVehicleJourneyRef");
  }

  private TripAndVehicleKey getKeyForVehicleActivity(
      VehicleActivityStructure vehicleActivity) {

    MonitoredVehicleJourney mvj = vehicleActivity.getMonitoredVehicleJourney();

    /**
     * VehicleRef is not required, but we prefer to use the vehicle id as our
     * primary key if it is present.
     */
    VehicleRefStructure vehicleRef = mvj.getVehicleRef();
    if (vehicleRef != null && vehicleRef.getValue() != null) {
      return TripAndVehicleKey.fromVehicleId(vehicleRef.getValue());
    }

    FramedVehicleJourneyRefStructure fvjRef = mvj.getFramedVehicleJourneyRef();
    return TripAndVehicleKey.fromTripIdAndServiceDate(
        fvjRef.getDatedVehicleJourneyRef(), fvjRef.getDataFrameRef().getValue());
  }

  private boolean isNewDataProducerOfLowerPriorityThanExistingDataProducer(
      TripAndVehicleKey key, String producer) {
    VehicleData data = _dataByVehicle.get(key);
    /**
     * If there is no existing data, then there is no "existing" producer and
     * the new producer can't be less than nothing. Thus we return false. This
     * gets us the desired behavior of accepting a new data entry if there is no
     * existing data entry.
     */
    if (data == null) {
      return false;
    }
    int existingPriority = getPriorityForProducer(data.getProducer());
    int newPriority = getPriorityForProducer(producer);
    return newPriority < existingPriority;
  }

  private int getPriorityForProducer(String producer) {
    if (producer == null || _producerPriorities == null) {
      return -1;
    }
    Integer priority = _producerPriorities.get(producer);
    if (priority == null) {
      return -1;
    }
    return priority;
  }

  private void processSituation(SiriChannelInfo channelInfo,
      ServiceDelivery delivery, PtSituationElementStructure situation) {
    EntryQualifierStructure situationNumber = situation.getSituationNumber();
    if (situationNumber == null || situationNumber.getValue() == null) {
      _log.warn("PtSituationElement did not specify a SituationNumber");
    }
    String id = situationNumber.getValue();
    String producer = null;
    if (delivery.getProducerRef() != null)
      producer = delivery.getProducerRef().getValue();
    Date expirationTime = getExpirtationTimeForChannel(channelInfo);
    AlertData data = new AlertData(situation, producer, expirationTime);
    _alertDataById.put(id, data);
  }

  /**
   * If a service delivery is from a polling request-response connection, an
   * endpoint might indicate that a particular vehicle or alert is no longer
   * active simply by no longer including it in a response. We need some way of
   * distinguishing this case from a pub-sub connection, where we might see an
   * alert once initially without any follow-up updates, even though the alert
   * is still active.
   * 
   * We examine the {@link SiriClientRequest} in the {@link SiriChannelInfo} to
   * determine if the request is a polling request-response connection (see
   * {@link SiriClientRequest#isSubscribe()} and
   * {@link SiriClientRequest#getPollInterval()}). If true, we compute an
   * expiration time for the service delivery based on the current time and the
   * poll interval.
   * 
   * @param channelInfo
   * @return the service delivery expiration time, or null if not applicable
   */
  private Date getExpirtationTimeForChannel(SiriChannelInfo channelInfo) {
    List<SiriClientRequest> requests = channelInfo.getSiriClientRequests();
    if (requests.isEmpty()) {
      return null;
    }
    Date exprirationTime = null;
    for (SiriClientRequest request : requests) {
      if (request.isSubscribe()) {
        return null;
      }
      Date date = new Date(
          (long) ((1.5 * request.getPollInterval() * 1000) + System.currentTimeMillis()));
      if (exprirationTime == null || exprirationTime.before(date)) {
        exprirationTime = date;
      }
    }
    return exprirationTime;
  }

  private void writeOutput() throws IOException {
    writeTripUpdates();
    writeVehiclePositions();
    writeAlerts();
    _gtfsRealtimeProvider.fireUpdate();
  }

  private void writeTripUpdates() throws IOException {

    FeedMessage.Builder feedMessageBuilder = GtfsRealtimeLibrary.createFeedMessageBuilder();
    long feedTimestamp = feedMessageBuilder.getHeader().getTimestamp() * 1000;

    Date durationOffset = new Date(feedTimestamp);

    for (Iterator<VehicleData> it = _dataByVehicle.values().iterator(); it.hasNext();) {
      VehicleData data = it.next();
      if (isDataStale(data, feedTimestamp)) {
        it.remove();
        continue;
      }

      VehicleActivityStructure activity = data.getVehicleActivity();

      MonitoredVehicleJourney mvj = activity.getMonitoredVehicleJourney();

      if (hasTripUpdateMonitoringError(mvj)) {
        _tripUpdateMonitoringErrorCount++;
        continue;
      }

      Duration delayDuration = mvj.getDelay();
      if (delayDuration == null)
        continue;
      int delayInSeconds = (int) (delayDuration.getTimeInMillis(durationOffset) / 1000);

      TripUpdate.Builder tripUpdate = TripUpdate.newBuilder();

      TripDescriptor td = getMonitoredVehicleJourneyAsTripDescriptor(mvj);
      tripUpdate.setTrip(td);

      VehicleDescriptor vd = getMonitoredVehicleJourneyAsVehicleDescriptor(mvj);
      if (vd != null) {
        tripUpdate.setVehicle(vd);
      }

      OneBusAwayTripUpdate.Builder obaTripUpdate = OneBusAwayTripUpdate.newBuilder();
      Date time = activity.getRecordedAtTime();
      if (time == null)
        time = new Date(feedTimestamp);
      obaTripUpdate.setTimestamp(time.getTime() / 1000);

      applyStopSpecificDelayToTripUpdateIfApplicable(mvj, delayInSeconds,
          tripUpdate);
      obaTripUpdate.setDelay(delayInSeconds);
      
      tripUpdate.setExtension(GtfsRealtimeOneBusAway.obaTripUpdate, obaTripUpdate.build());

      FeedEntity.Builder entity = FeedEntity.newBuilder();
      entity.setId(getTripIdForMonitoredVehicleJourney(mvj));
      if (data.getProducer() != null) {
        OneBusAwayFeedEntity.Builder obaFeedEntity = OneBusAwayFeedEntity.newBuilder();
        obaFeedEntity.setSource(data.getProducer());        
        entity.setExtension(GtfsRealtimeOneBusAway.obaFeedEntity, obaFeedEntity.build());
      }

      entity.setTripUpdate(tripUpdate);
      feedMessageBuilder.addEntity(entity);
      _tripUpdateCount++;
    }

    _gtfsRealtimeProvider.setTripUpdates(feedMessageBuilder.build(), false);
  }

  private void applyStopSpecificDelayToTripUpdateIfApplicable(
      MonitoredVehicleJourney mvj, int delayInSeconds,
      TripUpdate.Builder tripUpdate) {
    MonitoredCallStructure mc = mvj.getMonitoredCall();
    if (mc == null) {
      return;
    }

    StopPointRefStructure stopPointRef = mc.getStopPointRef();
    if (stopPointRef == null || stopPointRef.getValue() == null) {
      return;
    }

    StopTimeEvent.Builder stopTimeEvent = StopTimeEvent.newBuilder();
    stopTimeEvent.setDelay(delayInSeconds);

    StopTimeUpdate.Builder stopTimeUpdate = StopTimeUpdate.newBuilder();
    stopTimeUpdate.setDeparture(stopTimeEvent);
    stopTimeUpdate.setStopId(_idService.id(stopPointRef.getValue()));
    tripUpdate.addStopTimeUpdate(stopTimeUpdate);
  }

  private String getTripIdForMonitoredVehicleJourney(MonitoredVehicleJourney mvj) {
    StringBuilder b = new StringBuilder();
    FramedVehicleJourneyRefStructure fvjRef = mvj.getFramedVehicleJourneyRef();
    b.append(_idService.id(fvjRef.getDatedVehicleJourneyRef()));
    b.append('-');
    b.append(_idService.id(fvjRef.getDataFrameRef().getValue()));
    if (mvj.getVehicleRef() != null && mvj.getVehicleRef().getValue() != null) {
      b.append('-');
      b.append(_idService.id(mvj.getVehicleRef().getValue()));
    }
    return b.toString();
  }

  private void writeVehiclePositions() throws IOException {

    FeedMessage.Builder feedMessageBuilder = GtfsRealtimeLibrary.createFeedMessageBuilder();
    long feedTimestamp = feedMessageBuilder.getHeader().getTimestamp() * 1000;

    for (Iterator<VehicleData> it = _dataByVehicle.values().iterator(); it.hasNext();) {
      VehicleData data = it.next();
      if (isDataStale(data, feedTimestamp)) {
        it.remove();
        continue;
      }

      TripAndVehicleKey key = data.getKey();
      VehicleActivityStructure activity = data.getVehicleActivity();

      MonitoredVehicleJourney mvj = activity.getMonitoredVehicleJourney();
      LocationStructure location = mvj.getVehicleLocation();

      if (hasVehiclePositionMonitoringError(mvj)) {
        _vehiclePositionMonitoringErrorCount++;
        continue;
      }

      if (location != null && location.getLatitude() != null
          && location.getLongitude() != null) {

        VehiclePosition.Builder vp = VehiclePosition.newBuilder();

        TripDescriptor td = getMonitoredVehicleJourneyAsTripDescriptor(mvj);
        vp.setTrip(td);

        VehicleDescriptor vd = getMonitoredVehicleJourneyAsVehicleDescriptor(mvj);
        if (vd != null) {
          vp.setVehicle(vd);
        }

        Date time = activity.getRecordedAtTime();
        if (time == null)
          time = new Date(feedTimestamp);
        vp.setTimestamp(time.getTime() / 1000);

        Position.Builder position = Position.newBuilder();
        position.setLatitude(location.getLatitude().floatValue());
        position.setLongitude(location.getLongitude().floatValue());
        vp.setPosition(position);

        FeedEntity.Builder entity = FeedEntity.newBuilder();
        entity.setId(getVehicleIdForKey(key));
        if (data.getProducer() != null) {
          OneBusAwayFeedEntity.Builder obaFeedEntity = OneBusAwayFeedEntity.newBuilder();
          obaFeedEntity.setSource(data.getProducer());        
          entity.setExtension(GtfsRealtimeOneBusAway.obaFeedEntity, obaFeedEntity.build());
        }

        entity.setVehicle(vp);
        feedMessageBuilder.addEntity(entity);
        _vehiclePositionCount++;
      }
    }

    _gtfsRealtimeProvider.setVehiclePositions(feedMessageBuilder.build(), false);
  }

  private String getVehicleIdForKey(TripAndVehicleKey key) {
    if (key.getVehicleId() != null) {
      return _idService.id(key.getVehicleId());
    }
    return _idService.id(key.getTripId()) + "-"
        + _idService.id(key.getServiceDate());
  }

  private void writeAlerts() {
    FeedMessage.Builder feedMessageBuilder = GtfsRealtimeLibrary.createFeedMessageBuilder();

    Date now = new Date();

    for (Iterator<AlertData> it = _alertDataById.values().iterator(); it.hasNext();) {
      AlertData data = it.next();

      PtSituationElementStructure situation = data.getSituation();

      /**
       * If the situation has been closed or has expired, we no longer show the
       * alert in the GTFS-realtime feed.
       */
      if (SiriLibrary.isSituationClosed(situation)
          || SiriLibrary.isSituationExpired(situation, now)
          || isAlertDataExpired(data)) {
        it.remove();
        continue;
      }

      /**
       * If the situation is not in a valid period, we no longer show the alert
       * in the GTFS-realtime feed.
       */
      if (!SiriLibrary.isSituationPublishedOrValid(situation, now)) {
        continue;
      }

      Alert.Builder alert = _alertFactory.createAlertFromSituation(situation);

      FeedEntity.Builder entity = FeedEntity.newBuilder();
      entity.setId(situation.getSituationNumber().getValue());

      if (data.getProducer() != null) {
        OneBusAwayFeedEntity.Builder obaFeedEntity = OneBusAwayFeedEntity.newBuilder();
        obaFeedEntity.setSource(data.getProducer());        
        entity.setExtension(GtfsRealtimeOneBusAway.obaFeedEntity, obaFeedEntity.build());
      }

      entity.setAlert(alert);
      feedMessageBuilder.addEntity(entity);
    }

    _gtfsRealtimeProvider.setAlerts(feedMessageBuilder.build(), false);
  }

  public boolean isAlertDataExpired(AlertData data) {
    if (data.getExpirtationTime() == null) {
      return false;
    }
    return data.getExpirtationTime().before(new Date());
  }

  private boolean isDataStale(VehicleData data, long currentTime) {
    return data.getTimestamp() + _staleDataThreshold * 1000 < currentTime;
  }

  /**
   * 
   * @param mvj
   * @return true if MonitoredVehicleJourney.Monitored is false and
   *         MonitoredVehicleJourney.MonitoringError contains a string matching
   *         a value in {@link #_monitoringErrorsForTripUpdates}.
   */
  private boolean hasTripUpdateMonitoringError(MonitoredVehicleJourney mvj) {
    if (mvj.isMonitored() != null && mvj.isMonitored()) {
      return false;
    }
    List<String> errors = mvj.getMonitoringError();
    if (errors == null) {
      return false;
    }
    for (String error : errors) {
      if (_monitoringErrorsForTripUpdates.contains(error)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 
   * @param mvj
   * @return true if MonitoredVehicleJourney.Monitored is false and
   *         MonitoredVehicleJourney.MonitoringError contains a string matching
   *         a value in {@link #_monitoringErrorsForTripUpdates}.
   */
  private boolean hasVehiclePositionMonitoringError(MonitoredVehicleJourney mvj) {
    if (mvj.isMonitored() != null && mvj.isMonitored()) {
      return false;
    }
    List<String> errors = mvj.getMonitoringError();
    if (errors == null) {
      return false;
    }
    for (String error : errors) {
      if (_monitoringErrorsForVehiclePositions.contains(error)) {
        return true;
      }
    }
    return false;
  }

  private TripDescriptor getMonitoredVehicleJourneyAsTripDescriptor(
      MonitoredVehicleJourney mvj) {

    TripDescriptor.Builder td = TripDescriptor.newBuilder();
    FramedVehicleJourneyRefStructure fvjRef = mvj.getFramedVehicleJourneyRef();
    td.setTripId(_idService.id(fvjRef.getDatedVehicleJourneyRef()));

    return td.build();
  }

  private VehicleDescriptor getMonitoredVehicleJourneyAsVehicleDescriptor(
      MonitoredVehicleJourney mvj) {
    VehicleRefStructure vehicleRef = mvj.getVehicleRef();
    if (vehicleRef == null || vehicleRef.getValue() == null) {
      return null;
    }

    VehicleDescriptor.Builder vd = VehicleDescriptor.newBuilder();
    vd.setId(_idService.id(vehicleRef.getValue()));
    return vd.build();
  }

  /****
   * Internal Classes
   ****/

  private class ServiceDeliveryHandlerImpl implements
      SiriServiceDeliveryHandler {

    @Override
    public void handleServiceDelivery(SiriChannelInfo channelInfo,
        ServiceDelivery serviceDelivery) {
      _log.debug("delivery: channel={}", channelInfo.getContext());
      _deliveries.add(new Delivery(channelInfo, serviceDelivery));
      if (_rebuildOnEachDelivery) {
        try {
          processQueue();
        } catch (IOException ex) {
          _log.error("error processing incoming SIRI data", ex);
        }
      }
    }
  }

  private class SiriToGtfsRealtimeQueueProcessor implements Runnable {

    @Override
    public void run() {
      try {
        processQueue();
      } catch (Throwable ex) {
        _log.error("error processing incoming SIRI data", ex);
      }
    }
  }

  public static final class Delivery {
    public final SiriChannelInfo channelInfo;
    public final ServiceDelivery serviceDelivery;

    public Delivery(SiriChannelInfo channelInfo, ServiceDelivery serviceDelivery) {
      this.channelInfo = channelInfo;
      this.serviceDelivery = serviceDelivery;
    }
  }

}
