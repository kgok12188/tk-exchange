package com.tk.dispatch;

import org.apache.kafka.common.TopicPartition;

import java.util.Collections;
import java.util.Set;

/**
 * 持有 match_result 消费组当前分配到的 topic 与 partition 集合，供 rebalance 监听器更新、服务层读取。
 */
public final class AssignmentHolder {

    private volatile Set<String> assignedTopics = Collections.emptySet();
    private volatile Set<TopicPartition> assignedPartitions = Collections.emptySet();
    private volatile Set<String> previousTopics = Collections.emptySet();
    private volatile Set<TopicPartition> previousPartitions = Collections.emptySet();
    private final Object lock = new Object();

    /** 在 rebalance 分配时由 listener 调用，更新当前与“上一次”分配。 */
    public void update(Set<String> newTopics, Set<TopicPartition> newPartitions) {
        synchronized (lock) {
            previousTopics = assignedTopics;
            previousPartitions = assignedPartitions;
            assignedTopics = newTopics;
            assignedPartitions = newPartitions;
        }
    }

    public Set<String> getAssignedTopics() {
        return Collections.unmodifiableSet(assignedTopics);
    }

    public Set<TopicPartition> getAssignedPartitions() {
        return Collections.unmodifiableSet(assignedPartitions);
    }

    Set<String> getPreviousTopics() {
        return previousTopics;
    }

    Set<TopicPartition> getPreviousPartitions() {
        return previousPartitions;
    }
}
