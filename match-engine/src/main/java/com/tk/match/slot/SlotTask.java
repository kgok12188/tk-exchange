package com.tk.match.slot;

import com.tk.match.engine.OrderCommandEnvelope;

/**
 * Task enqueued to a slot: either an order command or a snapshot request.
 * Worker processes in order to preserve snapshot consistency with order_req offset.
 */
public interface SlotTask {

    static SlotTask order(OrderCommandEnvelope envelope) {
        return new OrderCommandTask(envelope);
    }

    static SlotTask snapshot(String symbol) {
        return new SnapshotTask(symbol);
    }

    static SlotTask haEvent(HaEvent haEvent) {
        return new HaTask(haEvent);
    }

}
