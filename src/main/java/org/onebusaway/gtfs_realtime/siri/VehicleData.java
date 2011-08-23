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

import uk.org.siri.siri.VehicleActivityStructure;

class VehicleData {

  private final TripAndVehicleKey key;

  private final long timestamp;

  private final VehicleActivityStructure vehicleActivity;

  public VehicleData(TripAndVehicleKey key, long timestamp,
      VehicleActivityStructure vehicleActivity) {
    this.key = key;
    this.timestamp = timestamp;
    this.vehicleActivity = vehicleActivity;
  }
  
  public TripAndVehicleKey getKey() {
    return key;
  }

  public long getTimestamp() {
    return timestamp;
  }

  public VehicleActivityStructure getVehicleActivity() {
    return vehicleActivity;
  }
}
