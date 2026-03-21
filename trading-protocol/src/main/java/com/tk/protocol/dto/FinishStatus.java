package com.tk.protocol.dto;

/**
 * Terminal order status in FinishOrder.
 */
public enum FinishStatus {
    COMPLETED,
    CANCEL,
    PART_CANCEL,
    EXCEPTION,
    REJECT,
    /** LIMIT_MAKER order rejected because it would have crossed the spread (taker fill). */
    POST_ONLY_REJECT
}
