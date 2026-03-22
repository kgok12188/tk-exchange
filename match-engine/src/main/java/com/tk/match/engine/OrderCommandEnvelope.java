package com.tk.match.engine;

import lombok.Getter;

/**
 * Envelope for pushing an order_req message into a slot ringBuffer.
 * Carries symbol, raw JSON, and Kafka offset (orderReqOffset, used in TradeOrder as matchId).
 */
@Getter
public class OrderCommandEnvelope {

    private final String symbol;
    private final String rawJson;
    private final long orderReqOffset;
    private final long timestamp;

    public OrderCommandEnvelope(String symbol, String rawJson, long orderReqOffset, long timestamp) {
        this.symbol = symbol;
        this.rawJson = rawJson;
        this.orderReqOffset = orderReqOffset;
        this.timestamp = timestamp;
    }

}
