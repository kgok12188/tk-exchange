package com.tk.match.slot.event;

/**
 * 比对对齐进度：由 {@link com.tk.match.slot.MatchSlot#updateComparedOffset} 或一致性校验成功后放入 pendingSlotEvents，
 * consumeLoop drain 时转发到 Disruptor，更新 {@link com.tk.match.engine.OrderBook} 的 comparedFileOffset 与 comparedFileQueueStartIndex。
 *
 * @param slaveQueueStartIndex slave 文件队列上已对齐到的 Chronicle 末尾索引；无则 -1
 */
public record ComparedEvent(String symbol, long offset, long slaveQueueStartIndex) implements SlotEvent {

}
