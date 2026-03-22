package com.tk.protocol.dto;

/**
 * Instruction type for order_req_(symbol) topic.
 */
public enum CommandType {
    PUSH_ORDER,
    CANCEL_ORDER,
    /**
     * 更新本 symbol 的 {@link MarketConfig}（与撮合指令同 topic 同序）。
     */
    UPDATE_MARKET
}
