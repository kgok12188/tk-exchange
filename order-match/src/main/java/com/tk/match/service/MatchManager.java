package com.tk.match.service;

import com.tk.match.generator.TradeIdGeneratorImpl;
import com.tx.common.entity.MarketConfig;
import com.tx.common.service.MarketConfigService;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 撮合服务管理
 */
@Service
public class MatchManager {


    private static final Map<Long, MatchService> matchServiceMap = new HashMap<>();

    private final MarketConfigService marketConfigService;

    private final KafkaProducer<String, String> kafkaProducer;

    private final String servers;

    private final ApplicationContext applicationContext;

    private KafkaManager kafkaManager;

    public MatchManager(@Value("${kafka.servers}") String servers, KafkaManager kafkaManager, MarketConfigService marketConfigService, ApplicationContext applicationContext) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, servers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
        props.put(ProducerConfig.ACKS_CONFIG, "all"); // 消息确认机制
        props.put(ProducerConfig.RETRIES_CONFIG, 3);  // 失败重试次数
        kafkaProducer = new KafkaProducer<>(props);
        this.servers = servers;
        this.marketConfigService = marketConfigService;
        this.applicationContext = applicationContext;
        this.kafkaManager = kafkaManager;
    }

    public MatchService getMatchService(MarketConfig marketConfig) {
        if (StringUtils.endsWithIgnoreCase("cfd", marketConfig.getMatchType())) {
            return new CfdMatchService(kafkaProducer, marketConfig, new TradeIdGeneratorImpl(), servers, kafkaManager);
        } else {
            return null;
        }
    }

    @PostConstruct
    public void loadMarket() {
        List<MarketConfig> list = marketConfigService.lambdaQuery().list();
        for (MarketConfig marketConfig : list) {
            MatchService matchService = applicationContext.getBean(MatchManager.class).getMatchService(marketConfig);
            matchServiceMap.put(matchService.marketConfig.getId(), matchService);
            matchService.toMaster();
        }
    }


    public Map<Long, MatchService> getMatchServices() {
        return matchServiceMap;
    }

}
