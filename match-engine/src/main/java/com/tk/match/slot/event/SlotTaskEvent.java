package com.tk.match.slot.event;

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

    public enum Type {ORDER, SNAPSHOT, HA, START, MOVE_COMPARE_OFFSET}

    private Type type;
    private String symbol;
    private String rawJson;
    private long orderReqOffset;
    private HaEvent haEvent;
    private long compareOffset;
    /**
     * Chronicle 读起点；-1 表示未带索引
     */
    private long compareQueueIndex = -1L;

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

    public void setMoveCompareOffset(String symbol, long compareOffset, long compareQueueIndex) {
        this.type = Type.MOVE_COMPARE_OFFSET;
        this.symbol = symbol;
        this.compareOffset = compareOffset;
        this.compareQueueIndex = compareQueueIndex;
    }

    public void setStart() {
        this.type = Type.START;
        this.symbol = null;
        this.rawJson = null;
        this.haEvent = null;
    }
}
