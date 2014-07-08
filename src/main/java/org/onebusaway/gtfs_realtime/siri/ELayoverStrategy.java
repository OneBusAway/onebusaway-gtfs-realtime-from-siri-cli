/**
 * Copyright (C) 2014 Google, Inc.
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

public enum ELayoverStrategy {
  /**
   * If a vehicle is in a layover, it will be included unmodified in
   * GTFS-realtime output, with the original delay value specified in the input
   * SIRI.
   */
  NO_CHANGE,

  /**
   * If a vehicle is in a layover, it will be included in GTFS-realtime output,
   * but its delay value will be set to zero.
   */
  ON_TIME,

  /**
   * If a vehicle is in a layover, it will be pruned from GTFS-realtime output.
   */
  PRUNE
}
