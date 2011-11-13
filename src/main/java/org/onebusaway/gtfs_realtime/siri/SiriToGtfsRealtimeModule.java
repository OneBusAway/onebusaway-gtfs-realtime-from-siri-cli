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

import java.net.URL;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import com.google.inject.AbstractModule;
import com.google.inject.name.Names;

public class SiriToGtfsRealtimeModule extends AbstractModule {

  private URL _tripUpdatesUrl;

  private URL _vehiclePositionsUrl;

  public void setTripUpdatesUrl(URL tripUpdatesUrl) {
    _tripUpdatesUrl = tripUpdatesUrl;
  }

  public void setVehiclePositionsUrl(URL vehiclePositionsUrl) {
    _vehiclePositionsUrl = vehiclePositionsUrl;
  }

  @Override
  protected void configure() {
    bind(SiriToGtfsRealtimeService.class);
    bind(ScheduledExecutorService.class).annotatedWith(
        Names.named("SiriToGtfsRealtimeService")).toInstance(
        Executors.newSingleThreadScheduledExecutor());
    if (_tripUpdatesUrl != null) {
      bind(URL.class).annotatedWith(Names.named("tripUpdatesUrl")).toInstance(
          _tripUpdatesUrl);
      bind(TripUpdatesServlet.class);
    }
    if (_vehiclePositionsUrl != null) {
      bind(URL.class).annotatedWith(Names.named("vehiclePositionsUrl")).toInstance(
          _vehiclePositionsUrl);
      bind(VehiclePositionsServlet.class);
    }
  }

}
