package com.tk.match.slot.event;

/**
 * 快照请求事件：由 submitTakeSnapshot 放入 pendingSlotEvents，consumeLoop drain 时转发到 Disruptor。
 */
public record SnapshotEvent(String symbol) implements SlotEvent {

}
