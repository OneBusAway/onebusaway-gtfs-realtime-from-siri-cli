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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.onebusaway.siri.core.SiriChannelInfo;
import org.onebusaway.siri.core.SiriClient;
import org.onebusaway.siri.core.SiriClientRequest;
import org.onebusaway.siri.core.SiriTypeFactory;
import org.onebusaway.siri.core.handlers.SiriServiceDeliveryHandler;
import org.onebusway.gtfs_realtime.exporter.GtfsRealtimeProviderImpl;

import uk.org.siri.siri.FramedVehicleJourneyRefStructure;
import uk.org.siri.siri.LocationStructure;
import uk.org.siri.siri.MonitoredCallStructure;
import uk.org.siri.siri.PtSituationElementStructure;
import uk.org.siri.siri.ServiceDelivery;
import uk.org.siri.siri.SituationExchangeDeliveryStructure;
import uk.org.siri.siri.SituationExchangeDeliveryStructure.Situations;
import uk.org.siri.siri.VehicleActivityStructure;
import uk.org.siri.siri.VehicleActivityStructure.MonitoredVehicleJourney;
import uk.org.siri.siri.VehicleMonitoringDeliveryStructure;

import com.google.transit.realtime.GtfsRealtime.Alert;
import com.google.transit.realtime.GtfsRealtime.Alert.Effect;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedHeader;
import com.google.transit.realtime.GtfsRealtime.FeedHeader.Incrementality;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.Position;
import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import com.google.transit.realtime.GtfsRealtime.TripUpdate;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeEvent;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate;
import com.google.transit.realtime.GtfsRealtime.VehicleDescriptor;
import com.google.transit.realtime.GtfsRealtime.VehiclePosition;
import com.google.transit.realtime.GtfsRealtimeConstants;

public class SiriToGtfsRealtimeServiceTest {

  private SiriToGtfsRealtimeService _service;
  private ScheduledExecutorService _executor;
  private SiriClient _client;
  private AlertFactory _alertFactory;
  private GtfsRealtimeProviderImpl _provider;

  @Before
  public void setup() throws IOException {
    _service = new SiriToGtfsRealtimeService();

    _executor = Mockito.mock(ScheduledExecutorService.class);
    _service.setScheduledExecutorService(_executor);

    _client = Mockito.mock(SiriClient.class);
    _service.setClient(_client);

    _alertFactory = Mockito.mock(AlertFactory.class);
    _service.setAlertFactory(_alertFactory);

    _service.setIdService(new IdService());

    _provider = new GtfsRealtimeProviderImpl();
    _service.setGtfsRealtimeProvider(_provider);
  }

  @Test
  public void test() throws Exception {

    _service.setUpdateFrequency(37);
    _service.setStaleDataThreshold(2);

    _service.start();

    ArgumentCaptor<Runnable> writeTaskCaptor = ArgumentCaptor.forClass(Runnable.class);
    Mockito.verify(_executor).scheduleAtFixedRate(writeTaskCaptor.capture(),
        Mockito.eq(0L), Mockito.eq(37L), Mockito.eq(TimeUnit.SECONDS));

    ArgumentCaptor<SiriServiceDeliveryHandler> handlerCaptor = ArgumentCaptor.forClass(SiriServiceDeliveryHandler.class);
    Mockito.verify(_client).addServiceDeliveryHandler(handlerCaptor.capture());

    Runnable writeTask = writeTaskCaptor.getValue();
    SiriServiceDeliveryHandler serviceDeliveryHandler = handlerCaptor.getValue();

    /**
     * First write with no service deliveries and verify an empty file
     */
    writeTask.run();

    FeedMessage tripUpdatesFeed = _provider.getTripUpdates();
    verifyHeader(tripUpdatesFeed);
    assertEquals(0, tripUpdatesFeed.getEntityCount());

    FeedMessage vehiclePositionFeed = _provider.getVehiclePositions();
    verifyHeader(vehiclePositionFeed);
    assertEquals(0, vehiclePositionFeed.getEntityCount());

    /**
     * Now we construct and send a vehicle activity update
     */
    SiriChannelInfo channelInfo = new SiriChannelInfo();
    ServiceDelivery serviceDelivery = new ServiceDelivery();

    VehicleMonitoringDeliveryStructure vm = new VehicleMonitoringDeliveryStructure();
    serviceDelivery.getVehicleMonitoringDelivery().add(vm);

    VehicleActivityStructure vehicleActivity = new VehicleActivityStructure();
    vm.getVehicleActivity().add(vehicleActivity);

    long recordedAt = System.currentTimeMillis();
    vehicleActivity.setRecordedAtTime(new Date(recordedAt));

    MonitoredVehicleJourney mvj = new MonitoredVehicleJourney();
    vehicleActivity.setMonitoredVehicleJourney(mvj);

    mvj.setDelay(SiriTypeFactory.duration(5 * 60 * 1000));

    FramedVehicleJourneyRefStructure fvjRef = new FramedVehicleJourneyRefStructure();
    fvjRef.setDataFrameRef(SiriTypeFactory.dataFrameRef("2011-08-23"));
    fvjRef.setDatedVehicleJourneyRef("MyFavoriteTrip");
    mvj.setFramedVehicleJourneyRef(fvjRef);

    MonitoredCallStructure mc = new MonitoredCallStructure();
    mc.setStopPointRef(SiriTypeFactory.stopPointRef("MyFavoriteStop"));
    mvj.setMonitoredCall(mc);

    LocationStructure location = new LocationStructure();
    location.setLatitude(BigDecimal.valueOf(47.653738));
    location.setLongitude(BigDecimal.valueOf(-122.307786));
    mvj.setVehicleLocation(location);

    mvj.setVehicleRef(SiriTypeFactory.vehicleRef("MyFavoriteBus"));

    serviceDeliveryHandler.handleServiceDelivery(channelInfo, serviceDelivery);

    /**
     * Now we write again
     */
    writeTask.run();

    tripUpdatesFeed = _provider.getTripUpdates();
    verifyHeader(tripUpdatesFeed);
    assertEquals(1, tripUpdatesFeed.getEntityCount());

    FeedEntity tripEntity = tripUpdatesFeed.getEntity(0);
    assertEquals("MyFavoriteTrip-2011-08-23-MyFavoriteBus", tripEntity.getId());
    assertFalse(tripEntity.hasAlert());
    assertFalse(tripEntity.hasVehicle());
    assertFalse(tripEntity.getIsDeleted());

    TripUpdate tripUpdate = tripEntity.getTripUpdate();
    TripDescriptor trip = tripUpdate.getTrip();
    assertEquals("MyFavoriteTrip", trip.getTripId());

    VehicleDescriptor vehicleDescriptor = tripUpdate.getVehicle();
    assertEquals("MyFavoriteBus", vehicleDescriptor.getId());

    assertEquals(1, tripUpdate.getStopTimeUpdateCount());
    StopTimeUpdate stopTimeUpdate = tripUpdate.getStopTimeUpdate(0);
    assertEquals("MyFavoriteStop", stopTimeUpdate.getStopId());
    assertFalse(stopTimeUpdate.hasArrival());

    StopTimeEvent stopTimeEvent = stopTimeUpdate.getDeparture();
    assertEquals(5 * 60, stopTimeEvent.getDelay());
    assertEquals(0, stopTimeEvent.getTime());
    assertEquals(0, stopTimeEvent.getUncertainty());

    vehiclePositionFeed = _provider.getVehiclePositions();
    verifyHeader(vehiclePositionFeed);
    assertEquals(1, vehiclePositionFeed.getEntityCount());

    FeedEntity vehicleEntity = vehiclePositionFeed.getEntity(0);
    assertEquals("MyFavoriteBus", vehicleEntity.getId());
    assertFalse(vehicleEntity.hasAlert());
    assertFalse(vehicleEntity.hasTripUpdate());
    VehiclePosition vehiclePosition = vehicleEntity.getVehicle();
    Position position = vehiclePosition.getPosition();
    assertEquals(location.getLatitude().floatValue(), position.getLatitude(),
        0.0);
    assertEquals(location.getLongitude().floatValue(), position.getLongitude(),
        0.0);
    vehicleDescriptor = vehiclePosition.getVehicle();
    assertEquals("MyFavoriteBus", vehicleDescriptor.getId());

    /**
     * Finally, we wait for a few seconds (a little bit more than the stale data
     * threshold)
     */
    Thread.sleep(3 * 1000);

    /**
     * We write one more time, verifying that the stale entries have now been
     * pruned
     */
    writeTask.run();

    tripUpdatesFeed = _provider.getTripUpdates();
    verifyHeader(tripUpdatesFeed);
    assertEquals(0, tripUpdatesFeed.getEntityCount());

    vehiclePositionFeed = _provider.getVehiclePositions();
    verifyHeader(vehiclePositionFeed);
    assertEquals(0, vehiclePositionFeed.getEntityCount());
  }

  @Test
  public void testIds() throws Exception {
    _service.start();

    ArgumentCaptor<Runnable> writeTaskCaptor = ArgumentCaptor.forClass(Runnable.class);
    Mockito.verify(_executor).scheduleAtFixedRate(writeTaskCaptor.capture(),
        Mockito.eq(0L), Mockito.anyInt(), Mockito.eq(TimeUnit.SECONDS));

    ArgumentCaptor<SiriServiceDeliveryHandler> handlerCaptor = ArgumentCaptor.forClass(SiriServiceDeliveryHandler.class);
    Mockito.verify(_client).addServiceDeliveryHandler(handlerCaptor.capture());

    Runnable writeTask = writeTaskCaptor.getValue();
    SiriServiceDeliveryHandler serviceDeliveryHandler = handlerCaptor.getValue();

    /**
     * Construct and send a low-priority vehicle activity update
     */
    SiriChannelInfo channelInfo = new SiriChannelInfo();
    ServiceDelivery serviceDelivery = createVehicleActivity("TripIdA",
        "2012-04-11", "v1", 5);

    serviceDeliveryHandler.handleServiceDelivery(channelInfo, serviceDelivery);

    /**
     * Write the feed and read the results
     */

    writeTask.run();

    FeedMessage tripUpdatesFeed = _provider.getTripUpdates();
    assertEquals(1, tripUpdatesFeed.getEntityCount());
    FeedEntity tripEntity = tripUpdatesFeed.getEntity(0);
    TripUpdate tripUpdate = tripEntity.getTripUpdate();
    TripDescriptor trip = tripUpdate.getTrip();
    assertEquals("TripIdA", trip.getTripId());
    VehicleDescriptor vehicle = tripUpdate.getVehicle();
    assertEquals("v1", vehicle.getId());
    StopTimeUpdate stopTimeUpdate = tripUpdate.getStopTimeUpdate(0);
    StopTimeEvent stopTimeEvent = stopTimeUpdate.getDeparture();
    assertEquals(5 * 60, stopTimeEvent.getDelay());

    /**
     * Send a new update for same vehicle but with different trip.
     */

    serviceDelivery = createVehicleActivity("TripIdB", "2012-04-11", "v1", 4);
    serviceDeliveryHandler.handleServiceDelivery(channelInfo, serviceDelivery);

    /**
     * Verify that the new update overwrites the old update.
     */

    writeTask.run();

    tripUpdatesFeed = _provider.getTripUpdates();
    assertEquals(1, tripUpdatesFeed.getEntityCount());
    tripEntity = tripUpdatesFeed.getEntity(0);
    tripUpdate = tripEntity.getTripUpdate();
    trip = tripUpdate.getTrip();
    assertEquals("TripIdB", trip.getTripId());
    vehicle = tripUpdate.getVehicle();
    assertEquals("v1", vehicle.getId());
    stopTimeUpdate = tripUpdate.getStopTimeUpdate(0);
    stopTimeEvent = stopTimeUpdate.getDeparture();
    assertEquals(4 * 60, stopTimeEvent.getDelay());

    /**
     * Send a new update with same trip id but no vehicle id.
     */

    serviceDelivery = createVehicleActivity("TripIdB", "2012-04-11", null, 3);
    serviceDeliveryHandler.handleServiceDelivery(channelInfo, serviceDelivery);

    /**
     * Verify that the new update is kept separate from the existing update (no
     * vehicle id to match it to the previous update).
     */

    writeTask.run();

    tripUpdatesFeed = _provider.getTripUpdates();
    assertEquals(2, tripUpdatesFeed.getEntityCount());

    tripEntity = getEntityWithId(tripUpdatesFeed, "TripIdB-2012-04-11-v1");
    tripUpdate = tripEntity.getTripUpdate();
    trip = tripUpdate.getTrip();
    assertEquals("TripIdB", trip.getTripId());
    vehicle = tripUpdate.getVehicle();
    assertEquals("v1", vehicle.getId());
    stopTimeUpdate = tripUpdate.getStopTimeUpdate(0);
    stopTimeEvent = stopTimeUpdate.getDeparture();
    assertEquals(4 * 60, stopTimeEvent.getDelay());

    tripEntity = getEntityWithId(tripUpdatesFeed, "TripIdB-2012-04-11");
    tripUpdate = tripEntity.getTripUpdate();
    trip = tripUpdate.getTrip();
    assertEquals("TripIdB", trip.getTripId());
    assertFalse(tripUpdate.hasVehicle());
    stopTimeUpdate = tripUpdate.getStopTimeUpdate(0);
    stopTimeEvent = stopTimeUpdate.getDeparture();
    assertEquals(3 * 60, stopTimeEvent.getDelay());

    /**
     * Send a new update with same trip id but no vehicle id.
     */

    serviceDelivery = createVehicleActivity("TripIdB", "2012-04-11", null, 2);
    serviceDeliveryHandler.handleServiceDelivery(channelInfo, serviceDelivery);

    /**
     * Verify that the new update is kept separate from the existing update (no
     * vehicle id to match it to the previous update).
     */

    writeTask.run();

    tripUpdatesFeed = _provider.getTripUpdates();
    assertEquals(2, tripUpdatesFeed.getEntityCount());

    tripEntity = getEntityWithId(tripUpdatesFeed, "TripIdB-2012-04-11-v1");
    tripUpdate = tripEntity.getTripUpdate();
    trip = tripUpdate.getTrip();
    assertEquals("TripIdB", trip.getTripId());
    vehicle = tripUpdate.getVehicle();
    assertEquals("v1", vehicle.getId());
    stopTimeUpdate = tripUpdate.getStopTimeUpdate(0);
    stopTimeEvent = stopTimeUpdate.getDeparture();
    assertEquals(4 * 60, stopTimeEvent.getDelay());

    tripEntity = getEntityWithId(tripUpdatesFeed, "TripIdB-2012-04-11");
    tripUpdate = tripEntity.getTripUpdate();
    trip = tripUpdate.getTrip();
    assertEquals("TripIdB", trip.getTripId());
    assertFalse(tripUpdate.hasVehicle());
    stopTimeUpdate = tripUpdate.getStopTimeUpdate(0);
    stopTimeEvent = stopTimeUpdate.getDeparture();
    assertEquals(2 * 60, stopTimeEvent.getDelay());
  }

  @Test
  public void testPollingServiceAlert() throws Exception {

    _service.start();

    ArgumentCaptor<Runnable> writeTaskCaptor = ArgumentCaptor.forClass(Runnable.class);
    Mockito.verify(_executor).scheduleAtFixedRate(writeTaskCaptor.capture(),
        Mockito.eq(0L), Mockito.anyInt(), Mockito.eq(TimeUnit.SECONDS));

    ArgumentCaptor<SiriServiceDeliveryHandler> handlerCaptor = ArgumentCaptor.forClass(SiriServiceDeliveryHandler.class);
    Mockito.verify(_client).addServiceDeliveryHandler(handlerCaptor.capture());

    Runnable writeTask = writeTaskCaptor.getValue();
    SiriServiceDeliveryHandler serviceDeliveryHandler = handlerCaptor.getValue();

    /**
     * Construct a service delivery with a service alert
     */
    SiriChannelInfo channelInfo = new SiriChannelInfo();

    /**
     * Indicate that our SIRI request is a polling request.
     */
    SiriClientRequest request = new SiriClientRequest();
    request.setSubscribe(false);
    request.setPollInterval(2);
    channelInfo.getSiriClientRequests().add(request);

    ServiceDelivery serviceDelivery = new ServiceDelivery();

    SituationExchangeDeliveryStructure sx = new SituationExchangeDeliveryStructure();
    serviceDelivery.getSituationExchangeDelivery().add(sx);

    Situations situations = new Situations();
    sx.setSituations(situations);

    PtSituationElementStructure situation = new PtSituationElementStructure();
    situation.setSituationNumber(SiriTypeFactory.entryQualifier("a1"));
    situations.getPtSituationElement().add(situation);

    Alert.Builder alertBuilder = Alert.newBuilder();
    alertBuilder.setEffect(Effect.DETOUR);
    Mockito.when(_alertFactory.createAlertFromSituation(situation)).thenReturn(
        alertBuilder);

    serviceDeliveryHandler.handleServiceDelivery(channelInfo, serviceDelivery);

    /**
     * Write the alerts
     */
    writeTask.run();

    /**
     * Read and verify the alert
     */
    FeedMessage alerts = _provider.getAlerts();
    verifyHeader(alerts);
    assertEquals(1, alerts.getEntityCount());

    FeedEntity alertEntity = alerts.getEntity(0);
    assertEquals("a1", alertEntity.getId());
    assertTrue(alertEntity.hasAlert());
    assertFalse(alertEntity.hasTripUpdate());
    assertFalse(alertEntity.hasVehicle());
    assertFalse(alertEntity.getIsDeleted());

    Alert alert = alertEntity.getAlert();
    assertEquals(Effect.DETOUR, alert.getEffect());

    /**
     * Sleep for a while longer than the poll request interval.
     */
    Thread.sleep(4 * 1000);

    /**
     * Write the alerts again
     */
    writeTask.run();

    /**
     * Verify that the polled alert has properly expired.
     */
    alerts = _provider.getAlerts();
    assertEquals(0, alerts.getEntityCount());
  }

  @Test
  public void testProducerPriorities() throws Exception {

    Map<String, Integer> producerPriorities = new HashMap<String, Integer>();
    producerPriorities.put("low", 5);
    producerPriorities.put("high", 10);
    _service.setProducerPriorities(producerPriorities);

    _service.start();

    ArgumentCaptor<Runnable> writeTaskCaptor = ArgumentCaptor.forClass(Runnable.class);
    Mockito.verify(_executor).scheduleAtFixedRate(writeTaskCaptor.capture(),
        Mockito.eq(0L), Mockito.anyInt(), Mockito.eq(TimeUnit.SECONDS));

    ArgumentCaptor<SiriServiceDeliveryHandler> handlerCaptor = ArgumentCaptor.forClass(SiriServiceDeliveryHandler.class);
    Mockito.verify(_client).addServiceDeliveryHandler(handlerCaptor.capture());

    Runnable writeTask = writeTaskCaptor.getValue();
    SiriServiceDeliveryHandler serviceDeliveryHandler = handlerCaptor.getValue();

    /**
     * Construct and send a low-priority vehicle activity update
     */
    SiriChannelInfo channelInfo = new SiriChannelInfo();
    ServiceDelivery serviceDelivery = createPrioritizedVehicleActivity("low", 5);

    serviceDeliveryHandler.handleServiceDelivery(channelInfo, serviceDelivery);

    /**
     * Write the feed and read the results
     */

    writeTask.run();

    FeedMessage tripUpdatesFeed = _provider.getTripUpdates();
    FeedEntity tripEntity = tripUpdatesFeed.getEntity(0);
    TripUpdate tripUpdate = tripEntity.getTripUpdate();
    StopTimeUpdate stopTimeUpdate = tripUpdate.getStopTimeUpdate(0);
    StopTimeEvent stopTimeEvent = stopTimeUpdate.getDeparture();
    assertEquals(5 * 60, stopTimeEvent.getDelay());

    /**
     * Send a new update at a higher priority
     */

    serviceDelivery = createPrioritizedVehicleActivity("high", 6);
    serviceDeliveryHandler.handleServiceDelivery(channelInfo, serviceDelivery);

    /**
     * Verify that the higher priority update overwrites the low-priority
     * update.
     */

    writeTask.run();

    tripUpdatesFeed = _provider.getTripUpdates();
    tripEntity = tripUpdatesFeed.getEntity(0);
    tripUpdate = tripEntity.getTripUpdate();
    stopTimeUpdate = tripUpdate.getStopTimeUpdate(0);
    stopTimeEvent = stopTimeUpdate.getDeparture();
    assertEquals(6 * 60, stopTimeEvent.getDelay());

    /**
     * Send a new update at a lower priority
     */

    serviceDelivery = createPrioritizedVehicleActivity("low", 4);
    serviceDeliveryHandler.handleServiceDelivery(channelInfo, serviceDelivery);

    /**
     * Verify that the lower priority update does not overwrite the
     * high-priority update
     */

    writeTask.run();

    tripUpdatesFeed = _provider.getTripUpdates();
    tripEntity = tripUpdatesFeed.getEntity(0);
    tripUpdate = tripEntity.getTripUpdate();
    stopTimeUpdate = tripUpdate.getStopTimeUpdate(0);
    stopTimeEvent = stopTimeUpdate.getDeparture();
    assertEquals(6 * 60, stopTimeEvent.getDelay());

    /**
     * Send a new update at a higher priority
     */

    serviceDelivery = createPrioritizedVehicleActivity("high", 3);
    serviceDeliveryHandler.handleServiceDelivery(channelInfo, serviceDelivery);

    /**
     * Verify that the higher priority update overwrites the existing
     * high-priority update.
     */

    writeTask.run();

    tripUpdatesFeed = _provider.getTripUpdates();
    tripEntity = tripUpdatesFeed.getEntity(0);
    tripUpdate = tripEntity.getTripUpdate();
    stopTimeUpdate = tripUpdate.getStopTimeUpdate(0);
    stopTimeEvent = stopTimeUpdate.getDeparture();
    assertEquals(3 * 60, stopTimeEvent.getDelay());
  }

  @Test
  public void testMonitoringErrors() throws Exception {

    _service.start();

    ArgumentCaptor<Runnable> writeTaskCaptor = ArgumentCaptor.forClass(Runnable.class);
    Mockito.verify(_executor).scheduleAtFixedRate(writeTaskCaptor.capture(),
        Mockito.eq(0L), Mockito.anyInt(), Mockito.eq(TimeUnit.SECONDS));

    ArgumentCaptor<SiriServiceDeliveryHandler> handlerCaptor = ArgumentCaptor.forClass(SiriServiceDeliveryHandler.class);
    Mockito.verify(_client).addServiceDeliveryHandler(handlerCaptor.capture());

    Runnable writeTask = writeTaskCaptor.getValue();
    SiriServiceDeliveryHandler serviceDeliveryHandler = handlerCaptor.getValue();

    /**
     * Construct and send a vehicle activity update
     */
    SiriChannelInfo channelInfo = new SiriChannelInfo();
    ServiceDelivery serviceDelivery = createVehicleActivity("trip-1",
        "2012-05-15", "123", 5);

    serviceDeliveryHandler.handleServiceDelivery(channelInfo, serviceDelivery);

    /**
     * Write the feed and read the results
     */

    writeTask.run();

    FeedMessage tripUpdatesFeed = _provider.getTripUpdates();
    assertEquals(1, tripUpdatesFeed.getEntityCount());

    FeedMessage vehiclePositionsFeed = _provider.getVehiclePositions();
    assertEquals(1, vehiclePositionsFeed.getEntityCount());

    /**
     * Send a new update with a monitoring error that should only affect the
     * trip update output
     */

    serviceDelivery = createVehicleActivity("trip-1", "2012-05-15", "123", 0);
    MonitoredVehicleJourney mvj = serviceDelivery.getVehicleMonitoringDelivery().get(
        0).getVehicleActivity().get(0).getMonitoredVehicleJourney();
    mvj.setMonitored(false);
    mvj.getMonitoringError().add(
        SiriToGtfsRealtimeService.MONITORING_ERROR_OFF_ROUTE);
    serviceDeliveryHandler.handleServiceDelivery(channelInfo, serviceDelivery);

    /**
     * Verify that the trip update with a monitoring error is now excluded but
     * the vehicle position is still included.
     */

    writeTask.run();

    tripUpdatesFeed = _provider.getTripUpdates();
    assertEquals(0, tripUpdatesFeed.getEntityCount());

    vehiclePositionsFeed = _provider.getVehiclePositions();
    assertEquals(1, vehiclePositionsFeed.getEntityCount());

    /**
     * Send a new update with a monitoring error that should only affect both
     * the trip update and vehicle position output
     */

    serviceDelivery = createVehicleActivity("trip-1", "2012-05-15", "123", 0);
    mvj = serviceDelivery.getVehicleMonitoringDelivery().get(0).getVehicleActivity().get(
        0).getMonitoredVehicleJourney();
    mvj.setMonitored(false);
    mvj.getMonitoringError().add(
        SiriToGtfsRealtimeService.MONITORING_ERROR_NO_CURRENT_INFORMATION);
    serviceDeliveryHandler.handleServiceDelivery(channelInfo, serviceDelivery);

    /**
     * Verify that the trip update with a monitoring error is now excluded but
     * the vehicle position is still included.
     */

    writeTask.run();

    tripUpdatesFeed = _provider.getTripUpdates();
    assertEquals(0, tripUpdatesFeed.getEntityCount());

    vehiclePositionsFeed = _provider.getVehiclePositions();
    assertEquals(0, vehiclePositionsFeed.getEntityCount());
  }

  /****
   * Private Methods
   ****/

  private ServiceDelivery createVehicleActivity(String tripId,
      String serviceDate, String vehicleId, int minutesLate) {

    ServiceDelivery serviceDelivery = new ServiceDelivery();

    VehicleMonitoringDeliveryStructure vm = new VehicleMonitoringDeliveryStructure();
    serviceDelivery.getVehicleMonitoringDelivery().add(vm);

    VehicleActivityStructure vehicleActivity = new VehicleActivityStructure();
    vm.getVehicleActivity().add(vehicleActivity);

    vehicleActivity.setRecordedAtTime(new Date(System.currentTimeMillis()));

    MonitoredVehicleJourney mvj = new MonitoredVehicleJourney();
    vehicleActivity.setMonitoredVehicleJourney(mvj);

    mvj.setDelay(SiriTypeFactory.duration(minutesLate * 60 * 1000));

    FramedVehicleJourneyRefStructure fvjRef = new FramedVehicleJourneyRefStructure();
    fvjRef.setDataFrameRef(SiriTypeFactory.dataFrameRef(serviceDate));
    fvjRef.setDatedVehicleJourneyRef(tripId);
    mvj.setFramedVehicleJourneyRef(fvjRef);

    MonitoredCallStructure mc = new MonitoredCallStructure();
    mc.setStopPointRef(SiriTypeFactory.stopPointRef("MyFavoriteStop"));
    mvj.setMonitoredCall(mc);

    LocationStructure location = new LocationStructure();
    location.setLatitude(BigDecimal.valueOf(47.653738));
    location.setLongitude(BigDecimal.valueOf(-122.307786));
    mvj.setVehicleLocation(location);

    if (vehicleId != null) {
      mvj.setVehicleRef(SiriTypeFactory.vehicleRef(vehicleId));
    }

    return serviceDelivery;
  }

  private ServiceDelivery createPrioritizedVehicleActivity(String priority,
      int minutesLate) {

    ServiceDelivery serviceDelivery = new ServiceDelivery();
    serviceDelivery.setProducerRef(SiriTypeFactory.particpantRef(priority));

    VehicleMonitoringDeliveryStructure vm = new VehicleMonitoringDeliveryStructure();
    serviceDelivery.getVehicleMonitoringDelivery().add(vm);

    VehicleActivityStructure vehicleActivity = new VehicleActivityStructure();
    vm.getVehicleActivity().add(vehicleActivity);

    vehicleActivity.setRecordedAtTime(new Date(System.currentTimeMillis()));

    MonitoredVehicleJourney mvj = new MonitoredVehicleJourney();
    vehicleActivity.setMonitoredVehicleJourney(mvj);

    mvj.setDelay(SiriTypeFactory.duration(minutesLate * 60 * 1000));

    FramedVehicleJourneyRefStructure fvjRef = new FramedVehicleJourneyRefStructure();
    fvjRef.setDataFrameRef(SiriTypeFactory.dataFrameRef("2011-08-23"));
    fvjRef.setDatedVehicleJourneyRef("MyFavoriteTrip");
    mvj.setFramedVehicleJourneyRef(fvjRef);

    MonitoredCallStructure mc = new MonitoredCallStructure();
    mc.setStopPointRef(SiriTypeFactory.stopPointRef("MyFavoriteStop"));
    mvj.setMonitoredCall(mc);

    LocationStructure location = new LocationStructure();
    location.setLatitude(BigDecimal.valueOf(47.653738));
    location.setLongitude(BigDecimal.valueOf(-122.307786));
    mvj.setVehicleLocation(location);

    mvj.setVehicleRef(SiriTypeFactory.vehicleRef("MyFavoriteBus"));

    return serviceDelivery;
  }

  private void verifyHeader(FeedMessage feed) {
    FeedHeader header = feed.getHeader();
    assertEquals(GtfsRealtimeConstants.VERSION, header.getGtfsRealtimeVersion());
    assertEquals(Incrementality.FULL_DATASET, header.getIncrementality());
    assertEquals((double) (System.currentTimeMillis() / 1000),
        header.getTimestamp(), 350);
  }

  private FeedEntity getEntityWithId(FeedMessage feed, String id) {
    for (int i = 0; i < feed.getEntityCount(); ++i) {
      FeedEntity entity = feed.getEntity(i);
      if (entity.getId().equals(id)) {
        return entity;
      }
    }
    return null;
  }
}
