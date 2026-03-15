package com.tk.match.slot;

/**
 * 待处理 slot 事件：由 consumeLoop 在 poll 前 drain 并处理。
 * - ADD_SYMBOL：需要上币，创建 MatchEngine 并将 order_req_(symbol) 加入 assign。
 * - BECAME_MASTER：已切换为主节点，触发文件队列补发后写 Kafka。
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
}
