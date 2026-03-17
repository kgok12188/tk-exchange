package com.tk.futures.result;

import com.alibaba.fastjson2.JSON;
import com.tk.protocol.dto.TradingResponse;
import com.tk.protocol.dto.UserCommandResult;
import com.tk.protocol.kafka.KafkaTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 将 trading-server 处理结果通过 Kafka 写回响应通道（response topic）。
 */
@Service
public class ResponsePublisher {

    private static final Logger logger = LoggerFactory.getLogger(ResponsePublisher.class);

    private final KafkaProducer<String, String> kafkaProducer;

    public ResponsePublisher(KafkaProducer<String, String> kafkaProducer) {
        this.kafkaProducer = kafkaProducer;
    }

    public void publish(String reqId, UserCommandResult result) {
        if (reqId == null || reqId.isEmpty() || result == null) {
            return;
        }
        TradingResponse response = TradingResponse.builder()
                .reqId(reqId)
                .result(result)
                .build();
        String payload = JSON.toJSONString(response);
        ProducerRecord<String, String> record = new ProducerRecord<>(KafkaTopic.RESPONSE, reqId, payload);
        try {
            kafkaProducer.send(record);
            if (logger.isDebugEnabled()) {
                logger.debug("published response to topic={}, reqId={}", KafkaTopic.RESPONSE, reqId);
            }
        } catch (Exception e) {
            logger.error("failed to publish response, reqId={}, payload={}", reqId, payload, e);
        }
    }
}

