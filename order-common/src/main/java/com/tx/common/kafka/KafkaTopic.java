package com.tx.common.kafka;

public class KafkaTopic {

    public static final String SYNC_TO_DB = "syncToDb";
    public static final String REQUEST_MESSAGE = "requestMessage"; // 实际topic 是 requestMessage + 分组名称
    public static final String RESPONSE_MESSAGE = "responseMessage"; // order-service 发送的响应消息


    public static final String ORDER_MATCH = "orderMatch";
    public static final String MARKET = "market-"; // market-${交易对标识}

    public static final String TRADE_PRICE = "tradePrice"; // 最新交易价格 {"marketId" : ${合约id}, "price": "价格" }

    public static final String MATCH_RECOVER = "recover-";
}
