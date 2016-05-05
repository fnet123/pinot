package com.linkedin.thirdeye.client.timeseries;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.joda.time.DateTime;

import com.google.common.collect.Lists;
import com.linkedin.thirdeye.api.TimeGranularity;
import com.linkedin.thirdeye.client.MetricFunction;
import com.linkedin.thirdeye.client.QueryCache;
import com.linkedin.thirdeye.client.pinot.PinotThirdEyeClient;

/** Manual test for verifying code works as expected (ie without exceptions thrown) */
public class TimeSeriesTest {
  private static final ArrayList<String> ABOOK_DIMENSIONS =
      Lists.newArrayList("browserName", "contactsOrigin", "deviceName", "continent", "countryCode",
          "environment", "locale", "osName", "pageKey", "source", "sourceApp");
  private static final String THIRDEYE_ABOOK_OFFLINE = "thirdeyeAbook_OFFLINE";
  private static final String COUNT = "__COUNT";
  private static final MetricFunction DEFAULT_METRIC_FUNCTION =
      new MetricFunction(MetricFunction.SUM, COUNT);
  private static final DateTime START = new DateTime(2016, 1, 1, 00, 00);

  public static void main(String[] args) throws Exception {
    URL resource = null;// = System.class.get.getResource("log4j.properties");
    if (resource == null) {
      resource = TimeSeriesHandler.class.getClassLoader().getResource("logback.x");
    }
    PinotThirdEyeClient pinotThirdEyeClient = PinotThirdEyeClient.getDefaultTestClient(); // TODO
                                                                                          // make
                                                                                          // this
    // configurable

    QueryCache queryCache = new QueryCache(pinotThirdEyeClient, Executors.newFixedThreadPool(10));

    for (TimeSeriesRequest timeSeriesRequest : new TimeSeriesRequest[] {
        generateGroupByTimeRequest(), generateGroupByDimensionRequest(),
        generateGroupByTimeAndDimension()
    }) {
      try {
        TimeSeriesHandler handler = new TimeSeriesHandler(queryCache);
        long start = System.currentTimeMillis();
        TimeSeriesResponse response = handler.handle(timeSeriesRequest);
        long end = System.currentTimeMillis();
        System.out.println("Time taken:" + (end - start));
        System.out.println("Time taken:" + (end - start));
        for (int i = 0; i < response.getNumRows(); i++) {
          System.out.println(response.getRow(i));
        }
      } catch (Exception e) {
        System.out.println("Request failed: " + timeSeriesRequest);
        e.printStackTrace();
        System.exit(-1);
      }
    }
    System.out.println(
        "No exceptions encountered during testing... but you still need to check for data quality!");
    System.exit(0);
  }

  private static TimeSeriesRequest generateGroupByTimeRequest() {
    TimeSeriesRequest timeSeriesRequest = new TimeSeriesRequest();
    timeSeriesRequest.setCollectionName(THIRDEYE_ABOOK_OFFLINE);

    timeSeriesRequest.setStart(START);
    timeSeriesRequest.setEnd(START.plusDays(1));

    List<MetricFunction> metricFunctions = new ArrayList<>();
    metricFunctions.add(DEFAULT_METRIC_FUNCTION);
    timeSeriesRequest.setMetricFunctions(metricFunctions);
    timeSeriesRequest.setAggregationTimeGranularity(new TimeGranularity(1, TimeUnit.HOURS));
    return timeSeriesRequest;
  }

  private static TimeSeriesRequest generateGroupByDimensionRequest() {
    TimeSeriesRequest timeSeriesRequest = new TimeSeriesRequest();
    timeSeriesRequest.setCollectionName(THIRDEYE_ABOOK_OFFLINE);
    timeSeriesRequest.setStart(START);
    timeSeriesRequest.setEnd(START.plusHours(3));

    timeSeriesRequest.setGroupByDimensions(ABOOK_DIMENSIONS);
    List<MetricFunction> metricFunctions = new ArrayList<>();
    metricFunctions.add(DEFAULT_METRIC_FUNCTION);
    timeSeriesRequest.setMetricFunctions(metricFunctions);
    timeSeriesRequest.setAggregationTimeGranularity(null);
    return timeSeriesRequest;
  }

  private static TimeSeriesRequest generateGroupByTimeAndDimension() {
    TimeSeriesRequest timeSeriesRequest = new TimeSeriesRequest();
    timeSeriesRequest.setCollectionName(THIRDEYE_ABOOK_OFFLINE);
    timeSeriesRequest.setStart(START);
    timeSeriesRequest.setEnd(START.plusHours(3));
    timeSeriesRequest.setGroupByDimensions(ABOOK_DIMENSIONS);
    List<MetricFunction> metricFunctions = new ArrayList<>();
    metricFunctions.add(DEFAULT_METRIC_FUNCTION);
    timeSeriesRequest.setMetricFunctions(metricFunctions);
    timeSeriesRequest.setAggregationTimeGranularity(new TimeGranularity(1, TimeUnit.HOURS));
    return timeSeriesRequest;
  }
}