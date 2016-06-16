/**
 * Copyright (C) 2014-2016 LinkedIn Corp. (pinot-core@linkedin.com)
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

package com.linkedin.pinot.routing.builder;

import com.linkedin.pinot.common.utils.CommonConstants;
import com.linkedin.pinot.common.utils.SegmentNameBuilder;
import com.linkedin.pinot.routing.ServerToSegmentSetMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.helix.model.ExternalView;
import org.apache.helix.model.InstanceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Routing table builder for the Kafka low level consumer.
 */
public class KafkaLowLevelConsumerRoutingTableBuilder implements RoutingTableBuilder {
  private static final Logger LOGGER = LoggerFactory.getLogger(KafkaLowLevelConsumerRoutingTableBuilder.class);
  private static final int routingTableCount = 10;

  @Override
  public void init(Configuration configuration) {
    // No configuration at the moment
  }

  @Override
  public List<ServerToSegmentSetMap> computeRoutingTableFromExternalView(String tableName, ExternalView externalView,
      List<InstanceConfig> instanceConfigList) {
    // We build the routing table based off the external view here. What we want to do is to make sure that we uphold
    // the guarantees clients expect (no duplicate records, eventual consistency) and spreading the load as equally as
    // possible between the servers.
    //
    // Each Kafka partition contains a fraction of the data, so we need to make sure that we query all partitions.
    // Because in certain unlikely degenerate scenarios, we can consume overlapping data until segments are flushed (at
    // which point the overlapping data is discarded during the reconciliation process with the controller), we need to
    // ensure that the query that is sent has only one partition in CONSUMING state in order to avoid duplicate records.
    //
    // Because we also want to want to spread the load as equally as possible between servers, we use a weighted random
    // replica selection that favors picking replicas with fewer segments assigned to them, thus having an approximately
    // equal distribution of load between servers.
    //
    // For example, given three replicas with 1, 2 and 3 segments assigned to each, the replica with one segment should
    // have a weight of 2, which is the maximum segment count minus the segment count for that replica. Thus, each
    // replica other than the replica(s) with the maximum segment count should have a chance of getting a segment
    // assigned to it. This corresponds to alternative three below:
    //
    // Alternative 1 (weight is sum of segment counts - segment count in that replica):
    // (6 - 1) = 5 -> P(0.4166)
    // (6 - 2) = 4 -> P(0.3333)
    // (6 - 3) = 3 -> P(0.2500)
    //
    // Alternative 2 (weight is max of segment counts - segment count in that replica + 1):
    // (3 - 1) + 1 = 3 -> P(0.5000)
    // (3 - 2) + 1 = 2 -> P(0.3333)
    // (3 - 3) + 1 = 1 -> P(0.1666)
    //
    // Alternative 3 (weight is max of segment counts - segment count in that replica):
    // (3 - 1) = 2 -> P(0.6666)
    // (3 - 2) = 1 -> P(0.3333)
    // (3 - 3) = 0 -> P(0.0000)
    //
    // Of those three weighting alternatives, the third one has the smallest standard deviation of the number of
    // segments assigned per replica, so it corresponds to the weighting strategy used for segment assignment. Empirical
    // testing shows that for 20 segments and three replicas, the standard deviation of each alternative is respectively
    // 2.112, 1.496 and 0.853.
    //
    // This algorithm works as follows:
    // 1. Gather all segments and group them by Kafka partition, sorted by sequence number
    // 2. Ensure that for each partition, we have at most one partition in consuming state
    // 3. Sort all the segments to be used during assignment in ascending order of replicas
    // 4. For each segment to be used during assignment, pick a random replica, weighted by the number of segments
    //    assigned to each replica.

    // 1. Gather all segments and group them by Kafka partition, sorted by sequence number
    Map<String, SortedSet<String>> sortedSegmentsByKafkaPartition = new HashMap<String, SortedSet<String>>();
    for (String helixPartitionName : externalView.getPartitionSet()) {
      // Ignore segments that are not low level consumer segments
      if (!SegmentNameBuilder.Realtime.isRealtimeV2Name(helixPartitionName)) {
        continue;
      }

      String kafkaPartitionName = SegmentNameBuilder.Realtime.extractPartitionRange(helixPartitionName);
      SortedSet<String> segmentsForPartition = sortedSegmentsByKafkaPartition.get(kafkaPartitionName);

      // Create sorted set if necessary
      if (segmentsForPartition == null) {
        segmentsForPartition = new TreeSet<String>(new Comparator<String>() {
          @Override
          public int compare(String firstSegment, String secondSegment) {
            // Sort based on sequence number, falling back on string comparison in case there is an exception
            try {
              int firstSegmentSequenceNumber =
                  Integer.parseInt(SegmentNameBuilder.Realtime.extractSequenceNumber(firstSegment));
              int secondSegmentSequenceNumber =
                  Integer.parseInt(SegmentNameBuilder.Realtime.extractSequenceNumber(secondSegment));
              return Integer.compare(firstSegmentSequenceNumber, secondSegmentSequenceNumber);
            } catch (NumberFormatException e) {
              LOGGER.warn("Caught number format exception while comparing segments {} and {}", firstSegment,
                  secondSegment, e);
              return firstSegment.compareTo(secondSegment);
            }
          }
        });

        sortedSegmentsByKafkaPartition.put(kafkaPartitionName, segmentsForPartition);
      }

      segmentsForPartition.add(helixPartitionName);
    }

    // 2. Ensure that for each partition, we have at most one partition in consuming state
    Map<String, String> lastSegmentInConsumingStateByKafkaPartition = new HashMap<String, String>();
    for (String kafkaPartition : sortedSegmentsByKafkaPartition.keySet()) {
      SortedSet<String> sortedSegmentsForKafkaPartition = sortedSegmentsByKafkaPartition.get(kafkaPartition);
      String lastSegment = sortedSegmentsForKafkaPartition.last();

      // Only keep the segment if all replicas have it in CONSUMING state
      Map<String, String> helixPartitionState = externalView.getStateMap(lastSegment);
      for (String externalViewState : helixPartitionState.values()) {
        // Ignore ERROR state
        if (externalViewState.equalsIgnoreCase(
            CommonConstants.Helix.StateModel.RealtimeSegmentOnlineOfflineStateModel.ERROR)) {
          continue;
        }

        // Not all segments are in CONSUMING state, therefore don't consider the last segment assignable to CONSUMING
        // replicas
        if (externalViewState.equalsIgnoreCase(
            CommonConstants.Helix.StateModel.RealtimeSegmentOnlineOfflineStateModel.ONLINE)) {
          lastSegment = null;
          break;
        }
      }

      if (lastSegment != null) {
        lastSegmentInConsumingStateByKafkaPartition.put(kafkaPartition, lastSegment);
      }
    }

    // 3. Sort all the segments to be used during assignment in ascending order of replicas
    PriorityQueue<Pair<String, Set<String>>> segmentToReplicaSetQueue = new PriorityQueue<Pair<String, Set<String>>>(
        new Comparator<Pair<String, Set<String>>>() {
          @Override
          public int compare(Pair<String, Set<String>> firstPair, Pair<String, Set<String>> secondPair) {
            return Integer.compare(firstPair.getRight().size(), secondPair.getRight().size());
          }
        });

    for (Map.Entry<String, SortedSet<String>> entry : sortedSegmentsByKafkaPartition.entrySet()) {
      String kafkaPartition = entry.getKey();
      SortedSet<String> segments = entry.getValue();

      // The only segment name which is allowed to be in CONSUMING state or null
      String validConsumingSegment = lastSegmentInConsumingStateByKafkaPartition.get(kafkaPartition);

      for (String segment : segments) {
        Set<String> validReplicas = new HashSet<String>();
        Map<String, String> externalViewState = externalView.getStateMap(segment);

        for (Map.Entry<String, String> instanceAndStateEntry : externalViewState.entrySet()) {
          String instance = instanceAndStateEntry.getKey();
          String state = instanceAndStateEntry.getValue();

          // Replicas in ONLINE state are always allowed
          if (state.equalsIgnoreCase(CommonConstants.Helix.StateModel.RealtimeSegmentOnlineOfflineStateModel.ONLINE)) {
            validReplicas.add(instance);
            continue;
          }

          // Replicas in CONSUMING state are only allowed on the last segment
          if (state.equalsIgnoreCase(CommonConstants.Helix.StateModel.RealtimeSegmentOnlineOfflineStateModel.CONSUMING)
              && segment.equals(validConsumingSegment)) {
            validReplicas.add(instance);
          }
        }

        segmentToReplicaSetQueue.add(new ImmutablePair<String, Set<String>>(segment, validReplicas));
      }
    }

    // 4. For each segment to be used during assignment, pick a random replica, weighted by the number of segments
    //    assigned to each replica.
    List<ServerToSegmentSetMap> routingTables = new ArrayList<ServerToSegmentSetMap>(routingTableCount);
    for(int i = 0; i < routingTableCount; ++i) {
      Map<String, Set<String>> instanceToSegmentSetMap = new HashMap<String, Set<String>>();
      while (!segmentToReplicaSetQueue.isEmpty()) {
        Pair<String, Set<String>> segmentAndValidReplicaSet = segmentToReplicaSetQueue.poll();
        String segment = segmentAndValidReplicaSet.getKey();
        Set<String> validReplicaSet = segmentAndValidReplicaSet.getValue();

        String replica = pickWeightedRandomReplica(validReplicaSet, instanceToSegmentSetMap);
        if (replica != null) {
          instanceToSegmentSetMap.get(replica).add(segment);
        }
      }
    }

    return routingTables;
  }

  private String pickWeightedRandomReplica(Set<String> validReplicaSet,
      Map<String, Set<String>> instanceToSegmentSetMap) {
    Random random = new Random();

    // No replicas?
    if (validReplicaSet.isEmpty()) {
      return null;
    }

    // Only one valid replica?
    if (validReplicaSet.size() == 1) {
      return validReplicaSet.iterator().next();
    }

    // Find maximum segment count assigned to a replica
    String[] replicas = validReplicaSet.toArray(new String[validReplicaSet.size()]);
    int[] replicaSegmentCounts = new int[validReplicaSet.size()];

    int maxSegmentCount = 0;
    for (int i = 0; i < replicas.length; i++) {
      String replica = replicas[i];
      int replicaSegmentCount = instanceToSegmentSetMap.get(replica).size();
      replicaSegmentCounts[i] = replicaSegmentCount;

      if (maxSegmentCount < replicaSegmentCount) {
        maxSegmentCount = replicaSegmentCount;
      }
    }

    // Compute replica weights
    int[] replicaWeights = new int[validReplicaSet.size()];
    int totalReplicaWeights = 0;
    for (int i = 0; i < replicas.length; i++) {
      int replicaWeight = maxSegmentCount - replicaSegmentCounts[i];
      replicaWeights[i] = replicaWeight;
      totalReplicaWeights += replicaWeight;
    }

    // If all replicas are equal, just pick a random replica
    if (totalReplicaWeights == 0) {
      return replicas[random.nextInt(replicas.length)];
    }

    // Pick the proper replica given their respective weights
    int randomValue = random.nextInt(totalReplicaWeights);
    int i = 0;
    while(replicaWeights[i] == 0 || replicaWeights[i] <= randomValue) {
      randomValue -= replicaWeights[i];
      ++i;
    }

    return replicas[i];
  }
}
