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

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.xml.datatype.Duration;

import org.mortbay.jetty.Server;
import org.onebusaway.siri.core.SiriChannelInfo;
import org.onebusaway.siri.core.SiriClient;
import org.onebusaway.siri.core.SiriClientRequest;
import org.onebusaway.siri.core.exceptions.SiriMissingArgumentException;
import org.onebusaway.siri.core.handlers.SiriServiceDeliveryHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.org.siri.siri.FramedVehicleJourneyRefStructure;
import uk.org.siri.siri.LocationStructure;
import uk.org.siri.siri.MonitoredCallStructure;
import uk.org.siri.siri.ServiceDelivery;
import uk.org.siri.siri.StopPointRefStructure;
import uk.org.siri.siri.VehicleActivityStructure;
import uk.org.siri.siri.VehicleActivityStructure.MonitoredVehicleJourney;
import uk.org.siri.siri.VehicleMonitoringDeliveryStructure;
import uk.org.siri.siri.VehicleRefStructure;

import com.google.inject.name.Named;
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
import com.google.transit.realtime.GtfsRealtimeOneBusAway;

@Singleton
public class SiriToGtfsRealtimeService {

  private static Logger _log = LoggerFactory.getLogger(SiriToGtfsRealtimeService.class);

  private ScheduledExecutorService _executor;

  private SiriClient _client;

  private ServiceDeliveryHandlerImpl _serviceDeliveryHandler = new ServiceDeliveryHandlerImpl();

  private BlockingQueue<ServiceDelivery> _deliveries = new LinkedBlockingQueue<ServiceDelivery>();

  private Map<TripAndVehicleKey, VehicleData> _dataByVehicle = new HashMap<TripAndVehicleKey, VehicleData>();

  private long _idIndex = 0;

  private List<SiriClientRequest> _clientRequests = new ArrayList<SiriClientRequest>();

  private File _tripUpdatesFile;

  private File _vehiclePositionsFile;

  /**
   * How often we update the output files, in seconds
   */
  private int _updateFrequency = 10;

  /**
   * Time, in seconds, after which a vehicle update is considered stale
   */
  private int _staleDataThreshold = 5 * 60;

  private Server _server;

  private volatile FeedMessage _tripUpdatesMessage = createFeedMessageBuilderWithHeader(
      System.currentTimeMillis()).build();

  private volatile FeedMessage _vehiclePositionsMessage = createFeedMessageBuilderWithHeader(
      System.currentTimeMillis()).build();

  @Inject
  public void setClient(SiriClient client) {
    _client = client;
  }

  @Inject
  public void setScheduledExecutorService(
      @Named("SiriToGtfsRealtimeService") ScheduledExecutorService executor) {
    _executor = executor;
  }

  public void addClientRequest(SiriClientRequest request) {
    _clientRequests.add(request);
  }

  public void setTripUpdatesFile(File tripUpdatesFile) {
    _tripUpdatesFile = tripUpdatesFile;
  }

  public void setVehiclePositionsFile(File vehiclePositionsFile) {
    _vehiclePositionsFile = vehiclePositionsFile;
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

  public FeedMessage getTripUpdatesMessage() {
    return _tripUpdatesMessage;
  }

  public FeedMessage getVehiclePositionsMessage() {
    return _vehiclePositionsMessage;
  }

  @PostConstruct
  public void start() throws Exception {

    _executor.scheduleAtFixedRate(new SiriToGtfsRealtimeQueueProcessor(), 0,
        _updateFrequency, TimeUnit.SECONDS);

    _client.addServiceDeliveryHandler(_serviceDeliveryHandler);

    for (SiriClientRequest request : _clientRequests)
      _client.handleRequest(request);
  }

  @PreDestroy
  public void stop() throws Exception {
    _client.removeServiceDeliveryHandler(_serviceDeliveryHandler);
    _executor.shutdownNow();
    if (_server != null) {
      _server.stop();
    }
  }

  /****
   * Private Methods
   ****/

  private void processQueue() throws IOException {

    List<ServiceDelivery> deliveries = new ArrayList<ServiceDelivery>();
    _deliveries.drainTo(deliveries);

    for (ServiceDelivery delivery : deliveries) {
      for (VehicleMonitoringDeliveryStructure vmDelivery : delivery.getVehicleMonitoringDelivery()) {
        for (VehicleActivityStructure vehicleActivity : vmDelivery.getVehicleActivity()) {

          try {
            processVehicleActivity(vehicleActivity);
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
    }

    writeOutput();
  }

  private void processVehicleActivity(VehicleActivityStructure vehicleActivity)
      throws SiriMissingArgumentException {

    /**
     * This is a required element.
     */
    MonitoredVehicleJourney mvj = vehicleActivity.getMonitoredVehicleJourney();
    if (mvj == null)
      throw new SiriMissingArgumentException("MonitoredVehicleJourney",
          "VehicleActivity");

    /**
     * This is a required element.
     */
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

    /**
     * This is NOT a required element.
     */
    VehicleRefStructure vehicleRef = mvj.getVehicleRef();
    String vehicleId = null;
    if (vehicleRef != null && vehicleRef.getValue() != null)
      vehicleId = vehicleRef.getValue();

    TripAndVehicleKey key = new TripAndVehicleKey(
        fvjRef.getDatedVehicleJourneyRef(),
        fvjRef.getDataFrameRef().getValue(), vehicleId);

    VehicleData data = new VehicleData(key, System.currentTimeMillis(),
        vehicleActivity);
    _dataByVehicle.put(key, data);
  }

  private void writeOutput() throws IOException {
    writeTripUpdates();
    writeVehiclePositions();
  }

  private void writeTripUpdates() throws IOException {

    long feedTimestamp = System.currentTimeMillis();
    FeedMessage.Builder feedMessageBuilder = createFeedMessageBuilderWithHeader(feedTimestamp);

    Date durationOffset = new Date(feedTimestamp);

    for (Iterator<VehicleData> it = _dataByVehicle.values().iterator(); it.hasNext();) {
      VehicleData data = it.next();
      if (isDataStale(data, feedTimestamp)) {
        it.remove();
        continue;
      }

      TripAndVehicleKey key = data.getKey();
      VehicleActivityStructure activity = data.getVehicleActivity();

      MonitoredVehicleJourney mvj = activity.getMonitoredVehicleJourney();

      Duration delayDuration = mvj.getDelay();
      if (delayDuration == null)
        continue;
      int delayInSeconds = (int) (delayDuration.getTimeInMillis(durationOffset) / 1000);

      TripUpdate.Builder tripUpdate = TripUpdate.newBuilder();

      TripDescriptor td = getKeyAsTripDescriptor(key);
      tripUpdate.setTrip(td);

      VehicleDescriptor vd = getKeyAsVehicleDescriptor(key);
      tripUpdate.setVehicle(vd);

      applyStopSpecificDelayToTripUpdateIfApplicable(mvj, delayInSeconds,
          tripUpdate);
      tripUpdate.setExtension(GtfsRealtimeOneBusAway.delay, delayInSeconds);

      FeedEntity.Builder entity = FeedEntity.newBuilder();
      entity.setId(getNextFeedEntityId());

      entity.setTripUpdate(tripUpdate);
      feedMessageBuilder.addEntity(entity);
    }

    FeedMessage message = feedMessageBuilder.build();
    _tripUpdatesMessage = message;

    if (_tripUpdatesFile != null) {
      BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(
          _tripUpdatesFile));
      message.writeTo(out);
      out.close();
    }
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
    stopTimeUpdate.setStopId(stopPointRef.getValue());
    tripUpdate.addStopTimeUpdate(stopTimeUpdate);
  }

  private void writeVehiclePositions() throws IOException {

    long feedTimestamp = System.currentTimeMillis();
    FeedMessage.Builder feedMessageBuilder = createFeedMessageBuilderWithHeader(feedTimestamp);

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

      if (location != null && location.getLatitude() != null
          && location.getLongitude() != null) {

        VehiclePosition.Builder vp = VehiclePosition.newBuilder();

        TripDescriptor td = getKeyAsTripDescriptor(key);
        vp.setTrip(td);

        VehicleDescriptor vd = getKeyAsVehicleDescriptor(key);
        vp.setVehicle(vd);

        Date time = activity.getRecordedAtTime();
        if (time == null)
          time = new Date(feedTimestamp);
        vp.setTimestamp(time.getTime());

        Position.Builder position = Position.newBuilder();
        position.setLatitude(location.getLatitude().floatValue());
        position.setLongitude(location.getLongitude().floatValue());
        vp.setPosition(position);

        FeedEntity.Builder entity = FeedEntity.newBuilder();
        entity.setId(getNextFeedEntityId());

        entity.setVehicle(vp);
        feedMessageBuilder.addEntity(entity);
      }
    }

    FeedMessage message = feedMessageBuilder.build();
    _vehiclePositionsMessage = message;

    if (_vehiclePositionsFile != null) {
      BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(
          _vehiclePositionsFile));
      message.writeTo(out);
      out.close();
    }
  }

  private boolean isDataStale(VehicleData data, long currentTime) {
    return data.getTimestamp() + _staleDataThreshold * 1000 < currentTime;
  }

  private String getNextFeedEntityId() {
    return Long.toString(_idIndex++);
  }

  private TripDescriptor getKeyAsTripDescriptor(TripAndVehicleKey key) {

    TripDescriptor.Builder td = TripDescriptor.newBuilder();
    td.setTripId(key.getTripId());

    return td.build();
  }

  private VehicleDescriptor getKeyAsVehicleDescriptor(TripAndVehicleKey key) {

    if (key.getVehicleId() == null)
      return null;

    VehicleDescriptor.Builder vd = VehicleDescriptor.newBuilder();
    vd.setId(key.getVehicleId());
    return vd.build();
  }

  private static FeedMessage.Builder createFeedMessageBuilderWithHeader(
      long feedTimestamp) {

    FeedHeader.Builder header = FeedHeader.newBuilder();
    header.setTimestamp(feedTimestamp);
    header.setIncrementality(Incrementality.FULL_DATASET);
    header.setGtfsRealtimeVersion(GtfsRealtimeConstants.VERSION);

    FeedMessage.Builder feedMessageBuilder = FeedMessage.newBuilder();
    feedMessageBuilder.setHeader(header);
    return feedMessageBuilder;
  }

  /****
   * Internal Classes
   ****/

  private class ServiceDeliveryHandlerImpl implements
      SiriServiceDeliveryHandler {

    @Override
    public void handleServiceDelivery(SiriChannelInfo channelInfo,
        ServiceDelivery serviceDelivery) {
      _deliveries.add(serviceDelivery);
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
}
