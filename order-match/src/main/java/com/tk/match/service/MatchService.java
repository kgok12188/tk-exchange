package com.tk.match.service;

import com.tx.common.entity.MarketConfig;
import org.apache.kafka.clients.producer.KafkaProducer;

public abstract class MatchService {

    protected final KafkaProducer<String, String> kafkaProducer;
    protected final MarketConfig marketConfig;

    public MatchService(KafkaProducer<String, String> kafkaProducer, MarketConfig marketConfig) {
        this.kafkaProducer = kafkaProducer;
        this.marketConfig = marketConfig;
    }

    public abstract void toMaster();

    public abstract void toSlave();

}
