package com.tk.match.slot;

import lombok.Getter;

/**
 * 快照请求事件：由 submitTakeSnapshot 放入 pendingSlotEvents，consumeLoop drain 时转发到 Disruptor。
 */
@Getter
public class SnapshotEvent implements SlotEvent {

    private final String symbol;

    public SnapshotEvent(String symbol) {
        this.symbol = symbol;
    }
}
