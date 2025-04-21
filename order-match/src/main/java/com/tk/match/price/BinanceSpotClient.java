package com.tk.match.price;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.tx.common.entity.MarketConfig;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class BinanceSpotClient extends WebSocketClient {

    private static final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(2);
    private static final Logger logger = LoggerFactory.getLogger(BinanceSpotClient.class);

    private final LinkedList<Consumer<String>> listenerList = new LinkedList<>();
    private volatile long lastUpdateTime;

    private final MarketConfig marketConfig;

    public BinanceSpotClient(MarketConfig marketConfig) throws URISyntaxException {
        //  super(new URI("wss://fstream.binance.com/ws"));
        super(new URI(JSON.parseObject(marketConfig.getJsonConfig()).getString("url")));
        lastUpdateTime = System.currentTimeMillis();
        this.marketConfig = marketConfig;
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        logger.info("开始订阅数据：{}", marketConfig.getSymbol());
        subscribe();
    }

    public void connect() {
        if (!this.isOpen()) {
            super.connect();
            scheduledExecutorService.schedule(this::subscribe, 120, TimeUnit.SECONDS);
        }
    }

    private void subscribe() {
        if (this.isOpen()) {
            String params = JSON.parseObject(marketConfig.getJsonConfig()).getString("params");
            scheduledExecutorService.schedule(this::subscribe, 120, TimeUnit.SECONDS);
            // 2. 订阅交易和盘口数据
            JSONObject subscribeMsg = new JSONObject();
            subscribeMsg.put("method", "SUBSCRIBE");
            subscribeMsg.put("params", Lists.newArrayList(params)); // 修改交易对
            subscribeMsg.put("id", System.currentTimeMillis());
            this.send(subscribeMsg.toString());
        }
    }

    @Override
    public void onMessage(String message) {
        long now = System.currentTimeMillis();
        if ((now - lastUpdateTime) > 1000 * 120) {
            lastUpdateTime = now;
            logger.info("message : {}", message);
        }
        for (Consumer<String> consumer : listenerList) {
            consumer.accept(message);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        logger.info("onClose : code = {},\treason = {},\tremote = {}", code, reason, remote);
    }

    @Override
    public void onError(Exception ex) {
        if (this.isOpen()) {
            this.reconnect();
        }
    }

    public void addListener(Consumer<String> consumer) {
        listenerList.add(consumer);
    }

}
