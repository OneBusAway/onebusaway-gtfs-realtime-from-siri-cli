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

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Date;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.onebusaway.siri.core.SiriChannelInfo;
import org.onebusaway.siri.core.SiriClient;
import org.onebusaway.siri.core.SiriTypeFactory;
import org.onebusaway.siri.core.handlers.SiriServiceDeliveryHandler;

import uk.org.siri.siri.FramedVehicleJourneyRefStructure;
import uk.org.siri.siri.LocationStructure;
import uk.org.siri.siri.MonitoredCallStructure;
import uk.org.siri.siri.ServiceDelivery;
import uk.org.siri.siri.VehicleActivityStructure;
import uk.org.siri.siri.VehicleActivityStructure.MonitoredVehicleJourney;
import uk.org.siri.siri.VehicleMonitoringDeliveryStructure;

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

  @Before
  public void setup() throws IOException {
    _service = new SiriToGtfsRealtimeService();

    _executor = Mockito.mock(ScheduledExecutorService.class);
    _service.setScheduledExecutorService(_executor);

    _client = Mockito.mock(SiriClient.class);
    _service.setClient(_client);
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

    FeedMessage tripUpdatesFeed = _service.getTripUpdates();
    verifyHeader(tripUpdatesFeed);
    assertEquals(0, tripUpdatesFeed.getEntityCount());

    FeedMessage vehiclePositionFeed = _service.getVehiclePositions();
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

    tripUpdatesFeed = _service.getTripUpdates();
    verifyHeader(tripUpdatesFeed);
    assertEquals(1, tripUpdatesFeed.getEntityCount());

    FeedEntity tripEntity = tripUpdatesFeed.getEntity(0);
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

    vehiclePositionFeed = _service.getVehiclePositions();
    verifyHeader(vehiclePositionFeed);
    assertEquals(1, vehiclePositionFeed.getEntityCount());

    FeedEntity vehicleEntity = vehiclePositionFeed.getEntity(0);
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

    tripUpdatesFeed = _service.getTripUpdates();
    verifyHeader(tripUpdatesFeed);
    assertEquals(0, tripUpdatesFeed.getEntityCount());

    vehiclePositionFeed = _service.getVehiclePositions();
    verifyHeader(vehiclePositionFeed);
    assertEquals(0, vehiclePositionFeed.getEntityCount());

  }

  private void verifyHeader(FeedMessage feed) {
    FeedHeader header = feed.getHeader();
    assertEquals(GtfsRealtimeConstants.VERSION, header.getGtfsRealtimeVersion());
    assertEquals(Incrementality.FULL_DATASET, header.getIncrementality());
    assertEquals((double) System.currentTimeMillis(), header.getTimestamp(),
        350);
  }
}
