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

class TripAndVehicleKey {

  /**
   * Required
   */
  private final String _tripId;

  /**
   * Required
   */
  private final String _serviceDate;

  /**
   * Optional
   */
  private final String _vehicleId;

  public TripAndVehicleKey(String tripId, String serviceDate, String vehicleId) {
    if (tripId == null)
      throw new IllegalArgumentException("tripId is null");
    if (serviceDate == null)
      throw new IllegalArgumentException("serviceDate is null");
    _tripId = tripId;
    _serviceDate = serviceDate;
    _vehicleId = vehicleId;
  }

  public String getTripId() {
    return _tripId;
  }

  public String getServiceDate() {
    return _serviceDate;
  }

  public String getVehicleId() {
    return _vehicleId;
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + _serviceDate.hashCode();
    result = prime * result + _tripId.hashCode();
    result = prime * result
        + ((_vehicleId == null) ? 0 : _vehicleId.hashCode());
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (obj == null)
      return false;
    if (getClass() != obj.getClass())
      return false;
    TripAndVehicleKey other = (TripAndVehicleKey) obj;
    if (!_tripId.equals(other._tripId))
      return false;
    if (!_serviceDate.equals(other._serviceDate))
      return false;
    if (_vehicleId == null) {
      if (other._vehicleId != null)
        return false;
    } else if (!_vehicleId.equals(other._vehicleId))
      return false;
    return true;
  }
  
  @Override
  public String toString() {
    return "tripId=" + _tripId + " vehicleId=" + _vehicleId;
  }
}
