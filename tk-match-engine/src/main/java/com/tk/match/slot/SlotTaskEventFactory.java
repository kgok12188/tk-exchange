package com.tk.match.slot;

import com.lmax.disruptor.EventFactory;
import com.tk.match.slot.event.SlotTaskEvent;

/**
 * Disruptor EventFactory for SlotTaskEvent (pre-allocated events in the ring).
 */
public final class SlotTaskEventFactory implements EventFactory<SlotTaskEvent> {

    @Override
    public SlotTaskEvent newInstance() {
        return new SlotTaskEvent();
    }


}
