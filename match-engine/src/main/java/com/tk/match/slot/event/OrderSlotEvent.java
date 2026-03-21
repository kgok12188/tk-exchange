package com.tk.match.slot.event;

/**
 * 订单事件：由 consumeLoop 将 Kafka 拉到的 order_req 放入 pendingSlotEvents，drain 时转发到 Disruptor。
 */
public record OrderSlotEvent(String symbol, String rawJson, long orderReqOffset) implements SlotEvent {

}
