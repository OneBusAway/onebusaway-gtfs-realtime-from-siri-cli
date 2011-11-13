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

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import com.google.protobuf.Message;

@Singleton
public class TripUpdatesServlet extends AbstractGtfsRealtimeServlet {

  private static final long serialVersionUID = 1L;

  private URL _url;

  private SiriToGtfsRealtimeService _service;

  @Inject
  public void setUrl(@Named("tripUpdatesUrl") URL url) {
    _url = url;
  }

  @Inject
  public void setService(SiriToGtfsRealtimeService service) {
    _service = service;
  }

  @Override
  public URL getUrl() {
    return _url;
  }

  @Override
  protected Message getMessage() {
    return _service.getTripUpdatesMessage();
  }
}
