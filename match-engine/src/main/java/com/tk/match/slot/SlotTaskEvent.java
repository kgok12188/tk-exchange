package com.tk.match.slot;

import lombok.Getter;
import lombok.Setter;

/**
 * Pre-allocated event for Disruptor RingBuffer in MatchSlot.
 * Carries ORDER (symbol + rawJson + orderReqOffset), SNAPSHOT (symbol), HA (HaEvent), or START (signal to create consumer thread).
 * Producers fill via setOrder / setSnapshot / setHa / setStart; consumer reads type and fields.
 */
@Setter
@Getter
public final class SlotTaskEvent {

    public enum Type {ORDER, SNAPSHOT, HA, START}

    private Type type;
    private String symbol;
    private String rawJson;
    private long orderReqOffset;
    private HaEvent haEvent;

    public void setOrder(String symbol, String rawJson, long orderReqOffset) {
        this.type = Type.ORDER;
        this.symbol = symbol;
        this.rawJson = rawJson;
        this.orderReqOffset = orderReqOffset;
        this.haEvent = null;
    }

    public void setSnapshot(String symbol) {
        this.type = Type.SNAPSHOT;
        this.symbol = symbol;
        this.rawJson = null;
        this.haEvent = null;
    }

    public void setHa(HaEvent haEvent) {
        this.type = Type.HA;
        this.symbol = null;
        this.rawJson = null;
        this.haEvent = haEvent;
    }

    public void setStart() {
        this.type = Type.START;
        this.symbol = null;
        this.rawJson = null;
        this.haEvent = null;
    }
}
