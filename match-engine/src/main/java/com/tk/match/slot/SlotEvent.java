package com.tk.match.slot;

/**
 * 待处理 slot 事件：由 consumeLoop 在 poll 前 drain 并处理；ORDER/SNAPSHOT/HA 由同一线程转发到 Disruptor（单生产者）。
 * - ADD_SYMBOL：需要上币，创建 MatchEngine 并将 order_req_(symbol) 加入 assign。
 * - BECAME_MASTER / BECAME_SLAVE：主从切换，转发到 Disruptor。
 * - ORDER：Kafka 订单，转发到 Disruptor。
 * - SNAPSHOT_REQUEST：快照请求，转发到 Disruptor。
 */
public interface SlotEvent {

    static SlotEvent addSymbol(String symbol) {
        return new AddSymbolEvent(symbol);
    }

    static SlotEvent addSymbol(String symbol, long initialMasterOffset) {
        return new AddSymbolEvent(symbol, initialMasterOffset);
    }

    static SlotEvent becameMaster() {
        return HaEvent.MASTER;
    }

    static SlotEvent becameSlave() {
        return HaEvent.SLAVE;
    }

    static SlotEvent order(String symbol, String rawJson, long orderReqOffset) {
        return new OrderSlotEvent(symbol, rawJson, orderReqOffset);
    }

    static SlotEvent snapshot(String symbol) {
        return new SnapshotEvent(symbol);
    }
}
