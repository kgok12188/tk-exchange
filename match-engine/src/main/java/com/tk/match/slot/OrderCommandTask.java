package com.tk.match.slot;

import com.tk.match.engine.OrderCommandEnvelope;

public class OrderCommandTask implements SlotTask {

    private final OrderCommandEnvelope envelope;

    public OrderCommandTask(OrderCommandEnvelope envelope) {
        this.envelope = envelope;
    }

    public OrderCommandEnvelope envelope() {
        return envelope;
    }
}
