package com.tk.match.slot;

import lombok.Getter;

/**
 * 订单事件：由 consumeLoop 将 Kafka 拉到的 order_req 放入 pendingSlotEvents，drain 时转发到 Disruptor。
 */
@Getter
public final class OrderSlotEvent implements SlotEvent {

    private final String symbol;
    private final String rawJson;
    private final long orderReqOffset;

    public OrderSlotEvent(String symbol, String rawJson, long orderReqOffset) {
        this.symbol = symbol;
        this.rawJson = rawJson;
        this.orderReqOffset = orderReqOffset;
    }
}
