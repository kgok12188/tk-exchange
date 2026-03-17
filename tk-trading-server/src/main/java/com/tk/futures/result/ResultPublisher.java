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

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 将结算产生的 AsyncMessageItems 写入 trading_result_(shard)。
 * 目前使用实例级 isMaster 标志控制是否输出，主从选主逻辑在后续任务中补齐。
 */
@Service
public class ResultPublisher {

    private static final Logger logger = LoggerFactory.getLogger(ResultPublisher.class);

    private final KafkaProducer<String, String> kafkaProducer;
    private final String shard;
    private final AtomicBoolean isMaster = new AtomicBoolean(true);

    public ResultPublisher(KafkaProducer<String, String> kafkaProducer,
                           @Value("${shard.id}") String shard) {
        this.kafkaProducer = kafkaProducer;
        this.shard = shard;
    }

    public void setMaster(boolean master) {
        boolean old = this.isMaster.getAndSet(master);
        if (old != master) {
            logger.info("ResultPublisher role changed: isMaster={}", master);
        }
    }

    /**
     * 将某个 slot 产生的一批事件写入对应 partition。
     *
     * @param partition partition = slotIndex
     * @param uid       该批事件所属用户（用于 key）
     * @param items     事件列表
     */
    public void publish(int partition, long uid, PersistenceBatchList items) {
        if (!isMaster.get()) {
            return;
        }
        if (items == null || items.isEmpty()) {
            return;
        }
        String topic = KafkaTopic.TRADING_RESULT + shard;
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, partition, String.valueOf(uid),
                JSON.toJSONString(items));
        try {
            kafkaProducer.send(record);
            if (logger.isDebugEnabled()) {
                logger.debug("published {} events to topic={}, partition={}, uid={}",
                        items.size(), topic, partition, uid);
            }
        } catch (Exception e) {
            logger.error("failed to publish AsyncMessageItems to topic={}, partition={}, uid={}, items={}",
                    topic, partition, uid, JSON.toJSONString(items), e);
        }
    }
}

