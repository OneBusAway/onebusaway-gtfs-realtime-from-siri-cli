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

  private final String _tripId;

  private final String _serviceDate;

  private final String _vehicleId;

  public static TripAndVehicleKey fromVehicleId(String vehicleId) {
    return new TripAndVehicleKey(null, null, vehicleId);
  }

  public static TripAndVehicleKey fromTripIdAndServiceDate(String tripId,
      String serviceDate) {
    return new TripAndVehicleKey(tripId, serviceDate, null);
  }

  private TripAndVehicleKey(String tripId, String serviceDate, String vehicleId) {
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
    result = prime * result
        + ((_serviceDate == null) ? 0 : _serviceDate.hashCode());
    result = prime * result + ((_tripId == null) ? 0 : _tripId.hashCode());
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
    if (_serviceDate == null) {
      if (other._serviceDate != null)
        return false;
    } else if (!_serviceDate.equals(other._serviceDate))
      return false;
    if (_tripId == null) {
      if (other._tripId != null)
        return false;
    } else if (!_tripId.equals(other._tripId))
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
    return "tripId=" + _tripId + " serviceDate=" + _serviceDate + " vehicleId="
        + _vehicleId;
  }

}
