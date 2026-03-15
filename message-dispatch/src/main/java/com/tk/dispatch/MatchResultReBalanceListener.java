package com.tk.dispatch;

import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 监听 match_result_* 消费组 rebalance：分区回收与分配时打点，并更新 {@link AssignmentHolder} 中的当前订阅视图。
 * 用于检测新 topic 与重新分区。
 */
public final class MatchResultReBalanceListener implements ConsumerRebalanceListener {

    private static final Logger log = LoggerFactory.getLogger(MatchResultReBalanceListener.class);

    private final AssignmentHolder assignmentHolder;

    public MatchResultReBalanceListener(AssignmentHolder assignmentHolder) {
        this.assignmentHolder = assignmentHolder;
    }

    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        if (partitions == null || partitions.isEmpty()) return;
        Set<String> topics = partitions.stream().map(TopicPartition::topic).collect(Collectors.toSet());
        log.info("match_result dispatch reBalance: partitions revoked, topics={}, partitionCount={}",
                topics, partitions.size());
    }

    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
        if (partitions == null) {
            assignmentHolder.update(Collections.emptySet(), Collections.emptySet());
            log.info("match_result dispatch rebalance: assigned partitions cleared");
            return;
        }
        Set<TopicPartition> newPartitions = new HashSet<>(partitions);
        Set<String> newTopics = newPartitions.stream().map(TopicPartition::topic).collect(Collectors.toSet());
        assignmentHolder.update(newTopics, newPartitions);

        Set<String> prevTopics = assignmentHolder.getPreviousTopics();
        Set<TopicPartition> prevPartitions = assignmentHolder.getPreviousPartitions();
        boolean newTopicsDetected = !newTopics.isEmpty() && !newTopics.equals(prevTopics);
        boolean partitionCountChanged = newPartitions.size() != prevPartitions.size() || !newPartitions.equals(prevPartitions);
        if (newTopicsDetected) {
            Set<String> added = new HashSet<>(newTopics);
            added.removeAll(prevTopics);
            log.info("match_result dispatch: new topic(s) detected and assigned, added={}, allTopics={}",
                    added, newTopics);
        }
        if (partitionCountChanged && !prevPartitions.isEmpty()) {
            log.info("match_result dispatch: partition assignment changed, before={}, after={}, totalPartitions={}",
                    prevPartitions.size(), newPartitions.size(), newPartitions);
        }
        log.info("match_result dispatch rebalance: partitions assigned, topics={}, partitionCount={}",
                newTopics, newPartitions.size());
    }
}
