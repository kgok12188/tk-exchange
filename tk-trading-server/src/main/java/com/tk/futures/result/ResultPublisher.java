package com.tk.futures.result;

import com.alibaba.fastjson2.JSON;
import com.tk.futures.model.PersistenceBatchList;
import com.tk.protocol.kafka.KafkaTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 将结算产生的 AsyncMessageItems 写入 trading_result_(shard)。
 * <p>
 * 语义：
 * - Kafka 健康时：异步 send（ringBuffer 线程不阻塞）
 * - Kafka 出现故障后：进入同步无限重试 drain（背压：不消费后续消息，直到 Kafka 恢复）
 * - consumer 仅在最终成功时回调一次（用于推进 pushOffset）
 */
@Service
public class ResultPublisher {

    private static final Logger logger = LoggerFactory.getLogger(ResultPublisher.class);

    private final KafkaProducer<String, String> kafkaProducer;
    private final String shard;

    private static final ConcurrentHashMap<Integer, PendingState> pendingStateLocal = new ConcurrentHashMap<>();

    public void flush(int partition) {
        PendingState state = pendingStateLocal.computeIfAbsent(partition, k -> new PendingState());
        drainPendingSync(state, partition, 0);
    }

    private static final class PendingState {
        // callback 线程向队列写、ringBuffer 线程向队列读，因此必须线程安全
        private final BlockingQueue<PendingRecord> pending = new LinkedBlockingQueue<>();
        private final AtomicBoolean faultMode = new AtomicBoolean(false);
        private final AtomicBoolean drainStatus = new AtomicBoolean(false);
    }

    private static final class PendingRecord {
        private final ProducerRecord<String, String> record;
        private final Consumer<Exception> consumer;

        private PendingRecord(ProducerRecord<String, String> record, Consumer<Exception> consumer) {
            this.record = record;
            this.consumer = consumer;
        }
    }

    public ResultPublisher(KafkaProducer<String, String> kafkaProducer,
                           @Value("${shard.id}") String shard) {
        this.kafkaProducer = kafkaProducer;
        this.shard = shard;
    }

    /**
     * 将某个 slot 产生的一批事件写入对应 partition。
     *
     * @param partition partition = slotIndex
     * @param uid       该批事件所属用户（用于 key）
     * @param items     事件列表
     */
    public void publish(int partition, long uid, long offset, PersistenceBatchList items, Consumer<Exception> consumer) {
        if (items == null || items.isEmpty()) return;
        publishRaw(partition, uid, offset, JSON.toJSONString(items), consumer);
    }

    /**
     * 以已序列化 JSON payload 发布（用于从节点文件队列 replay）。
     */
    public void publishRaw(int partition, long uid, long offset, String payloadJson, Consumer<Exception> consumer) {
        if (payloadJson == null || payloadJson.isEmpty()) return;

        String topic = KafkaTopic.TRADING_RESULT + shard;
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, partition, String.valueOf(uid), payloadJson);
        // 把当前 trading_(shard) 的 offset 写入 header，供下游/对账使用
        record.headers().add("offset", Long.toString(offset).getBytes(StandardCharsets.UTF_8));

        PendingState state = pendingStateLocal.computeIfAbsent(partition, k -> new PendingState());
        // 只有在已进入 faultMode（或 pending 里已有失败待重试）时，才在 ringBuffer 线程同步无限重试
        if (state.faultMode.get() || !state.pending.isEmpty()) {
            boolean offer = false;
            while (!offer) {
                offer = state.pending.isEmpty() && state.pending.offer(new PendingRecord(record, consumer));
                state.faultMode.set(true);
                drainPendingSync(state, partition, uid);
            }
        } else {
            kafkaProducer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    state.faultMode.set(true);
                    // fault 发生时：只入队，不回调 consumer（保证 consumer 只在最终成功时回调）
                    if (!state.pending.offer(new PendingRecord(record, consumer))) {
                        logger.error("callback ResultPublisher pending queue full, slotPartition={}, uid={}, offset={}",
                                partition, uid, offset);
                    }
                } else {
                    if (consumer != null) {
                        consumer.accept(null);
                    }
                }
            });
        }
    }

    private void drainPendingSync(PendingState state, int partition, long uid) {
        state.drainStatus.set(true);
        try {
            // 无限重试直到队列耗尽；期间有新故障会不断入队，并继续 drain
            while (true) {
                PendingRecord pr = state.pending.poll();
                if (pr == null) {
                    // 给 callback 线程一些时间把失败记录入队（避免竞争导致漏处理）
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    // 再次判断：如果队列确实耗尽，则清掉 faultMode 并退出（恢复健康后再次走异步）
                    if (state.pending.isEmpty()) {
                        state.faultMode.set(false);
                        return;
                    }
                    continue;
                }

                while (true) {
                    try {
                        Future<?> f = kafkaProducer.send(pr.record);
                        f.get(); // 等待 ACK，确保“最终成功才回调”
                        if (pr.consumer != null) {
                            pr.consumer.accept(null);
                        }
                        break;
                    } catch (Exception exception) {
                        logger.warn("ResultPublisher drain retry error, topicPartition={}, uid={}, err={}",
                                partition, uid, exception.getMessage());
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }
        } finally {
            state.drainStatus.set(false);
        }
    }

    public void check(Consumer<Integer> consumer) {
        for (Integer i : pendingStateLocal.keySet()) {
            if (!pendingStateLocal.get(i).drainStatus.get() && !pendingStateLocal.get(i).pending.isEmpty()) {
                consumer.accept(i);
            }
        }
    }

}

