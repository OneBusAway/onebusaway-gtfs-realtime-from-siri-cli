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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.Parser;
import org.apache.commons.cli.PosixParser;
import org.onebusaway.cli.Daemonizer;
import org.onebusaway.siri.core.SiriClient;
import org.onebusaway.siri.core.SiriClientRequest;
import org.onebusaway.siri.core.SiriClientRequestFactory;
import org.onebusaway.siri.core.SiriCoreModule;
import org.onebusaway.siri.core.SiriLibrary;
import org.onebusaway.siri.core.exceptions.SiriUnknownVersionException;
import org.onebusaway.siri.core.guice.LifecycleService;
import org.onebusaway.siri.core.versioning.ESiriVersion;
import org.onebusaway.siri.jetty.SiriJettyModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;

public class SiriToGtfsRealtimeMain {

  private static Logger _log = LoggerFactory.getLogger(SiriToGtfsRealtimeMain.class);

  private static final String ARG_ID = "id";

  private static final String ARG_CLIENT_URL = "clientUrl";

  private static final String ARG_PRIVATE_CLIENT_URL = "privateClientUrl";

  private static final String ARG_TRIP_UPDATES_PATH = "tripUpdatesPath";

  private static final String ARG_TRIP_UPDATES_URL = "tripUpdatesUrl";

  private static final String ARG_VEHICLE_POSITIONS_PATH = "vehiclePositionsPath";

  private static final String ARG_VEHICLE_POSITIONS_URL = "vehiclePositionsUrl";

  private static final String ARG_UPDATE_FREQUENCY = "updateFrequency";

  private static final String ARG_STALE_DATA_THRESHOLD = "staleDataThreshold";

  public static void main(String[] args) {
    try {
      SiriToGtfsRealtimeMain m = new SiriToGtfsRealtimeMain();
      m.run(args);
    } catch (Exception ex) {
      _log.error("error running application", ex);
      System.exit(-1);
    }
  }

  public void run(String[] args) throws Exception {

    Options options = new Options();
    buildOptions(options);
    Daemonizer.buildOptions(options);
    Parser parser = new PosixParser();
    CommandLine cli = parser.parse(options, args);
    Daemonizer.handleDaemonization(cli);

    List<Module> modules = new ArrayList<Module>();
    modules.addAll(SiriCoreModule.getModules());
    modules.add(new SiriJettyModule());
    modules.add(new SiriToGtfsRealtimeModule());
    Injector injector = Guice.createInjector(modules);

    configureClient(cli, injector);

    /**
     * Start the client and add all siri subscription requests
     */
    LifecycleService lifecycleService = injector.getInstance(LifecycleService.class);
    lifecycleService.start();
  }

  protected void buildOptions(Options options) {
    options.addOption(ARG_ID, true, "id");
    options.addOption(ARG_CLIENT_URL, true, "siri client url");
    options.addOption(ARG_PRIVATE_CLIENT_URL, true, "siri private client url");
    options.addOption(ARG_TRIP_UPDATES_PATH, true, "trip updates path");
    options.addOption(ARG_TRIP_UPDATES_URL, true, "trip updates url");
    options.addOption(ARG_VEHICLE_POSITIONS_PATH, true,
        "vehicle locations path");
    options.addOption(ARG_VEHICLE_POSITIONS_URL, true, "vehicle locations url");
    options.addOption(ARG_UPDATE_FREQUENCY, true, "update frequency");
    options.addOption(ARG_STALE_DATA_THRESHOLD, true, "stale data threshold");
  }

  private void printUsage() {
    InputStream is = getClass().getResourceAsStream("usage.txt");
    BufferedReader reader = new BufferedReader(new InputStreamReader(is));
    String line = null;
    try {
      while ((line = reader.readLine()) != null) {
        System.err.println(line);
      }
    } catch (IOException ex) {

    } finally {
      if (reader != null) {
        try {
          reader.close();
        } catch (IOException ex) {

        }
      }
    }
  }

  private void configureClient(CommandLine cli, Injector injector)
      throws MalformedURLException {

    SiriClient client = injector.getInstance(SiriClient.class);
    SiriToGtfsRealtimeService service = injector.getInstance(SiriToGtfsRealtimeService.class);

    if (cli.hasOption(ARG_ID))
      client.setIdentity(cli.getOptionValue(ARG_ID));

    if (cli.hasOption(ARG_CLIENT_URL))
      client.setUrl(cli.getOptionValue(ARG_CLIENT_URL));

    if (cli.hasOption(ARG_PRIVATE_CLIENT_URL))
      client.setPrivateUrl(cli.getOptionValue(ARG_PRIVATE_CLIENT_URL));

    boolean hasShareUrls = cli.hasOption(ARG_TRIP_UPDATES_URL)
        || cli.hasOption(ARG_VEHICLE_POSITIONS_URL);
    boolean hasSharePaths = cli.hasOption(ARG_TRIP_UPDATES_PATH)
        || cli.hasOption(ARG_VEHICLE_POSITIONS_PATH);

    if (!(hasShareUrls || hasSharePaths)) {
      System.err.println("ERROR: You did not specify a trip updates or vehicle positions output file or url.");
      printUsage();
      System.exit(-1);
    }

    if (cli.hasOption(ARG_TRIP_UPDATES_PATH)) {
      service.setTripUpdatesFile(new File(
          cli.getOptionValue(ARG_TRIP_UPDATES_PATH)));
    }
    if (cli.hasOption(ARG_TRIP_UPDATES_URL)) {
      service.setTripUpdatesUrl(new URL(
          cli.getOptionValue(ARG_TRIP_UPDATES_URL)));
    }

    if (cli.hasOption(ARG_VEHICLE_POSITIONS_PATH)) {
      service.setVehiclePositionsFile(new File(
          cli.getOptionValue(ARG_VEHICLE_POSITIONS_PATH)));
    }
    if (cli.hasOption(ARG_VEHICLE_POSITIONS_URL)) {
      service.setVehiclePositionsUrl(new URL(
          cli.getOptionValue(ARG_VEHICLE_POSITIONS_URL)));
    }

    if (cli.hasOption(ARG_UPDATE_FREQUENCY)) {
      int updateFrequency = Integer.parseInt(cli.getOptionValue(ARG_UPDATE_FREQUENCY));
      service.setUpdateFrequency(updateFrequency);
    }

    if (cli.hasOption(ARG_STALE_DATA_THRESHOLD)) {
      int staleDataThreshold = Integer.parseInt(cli.getOptionValue(ARG_STALE_DATA_THRESHOLD));
      service.setStaleDataThreshold(staleDataThreshold);
    }

    String[] args = cli.getArgs();

    if (args.length == 0) {
      System.err.println("ERROR: You did not specify any SIRI endpoint URLs to connect to!");
      printUsage();
      System.exit(-1);
    }

    SiriClientRequestFactory factory = new SiriClientRequestFactory();

    for (String arg : args) {
      SiriClientRequest request = getLineAsSubscriptionRequest(factory, arg);
      service.addClientRequest(request);
    }
  }

  private SiriClientRequest getLineAsSubscriptionRequest(
      SiriClientRequestFactory factory, String requestSpec) {

    try {

      Map<String, String> subArgs = SiriLibrary.getLineAsMap(requestSpec);
      return factory.createSubscriptionRequest(subArgs);

    } catch (SiriUnknownVersionException ex) {

      System.err.println("uknown siri version=\"" + ex.getVersion()
          + "\" in spec=" + requestSpec);
      System.err.println("supported versions:");
      for (ESiriVersion version : ESiriVersion.values())
        System.err.println("  " + version.getVersionId());
      System.exit(-1);
    }

    // Should never get here after System.exit(-1)
    return null;
  }
}
