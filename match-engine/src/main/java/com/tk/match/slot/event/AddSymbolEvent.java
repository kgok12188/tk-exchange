package com.tk.match.slot.event;

import lombok.Getter;

/**
 * 需要上币：consumeLoop 处理时创建 MatchEngine 并将 order_req_(symbol) 加入 assign。
 * initialMasterOffset：添加币对时查询 match_result_(symbol) 尾部得到的 orderReqOffset，用于设 OrderBook.masterOffset。
 */
@Getter
public final class AddSymbolEvent implements SlotEvent {

    private final String symbol;
    private final long initialMasterOffset;

    public AddSymbolEvent(String symbol) {
        this(symbol, 0L);
    }

    public AddSymbolEvent(String symbol, long initialMasterOffset) {
        this.symbol = symbol;
        this.initialMasterOffset = initialMasterOffset >= 0 ? initialMasterOffset : 0L;
    }

}
