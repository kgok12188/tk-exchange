package com.tk.match.slot;

import com.lmax.disruptor.EventFactory;

/**
 * Disruptor EventFactory for SlotTaskEvent (pre-allocated events in the ring).
 */
public final class SlotTaskEventFactory implements EventFactory<SlotTaskEvent> {

    @Override
    public SlotTaskEvent newInstance() {
        return new SlotTaskEvent();
    }


}
