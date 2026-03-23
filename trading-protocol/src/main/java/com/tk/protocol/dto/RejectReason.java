package com.tk.protocol.dto;

/**
 * Fine-grained reject reason for rejected terminal orders.
 * Keep {@link FinishStatus} as terminal status and use this enum to describe why it was rejected.
 */
public enum RejectReason {
    INVALID_ORDER_ID,
    DUPLICATE_ORDER_ID,
    ORDER_EXPIRED,
    INVALID_PRICE_TYPE,
    INVALID_PRICE,
    PRICE_TICK_INVALID,
    INVALID_QUANTITY,
    INVALID_NOTIONAL,
    INVALID_TIME_IN_FORCE,
    POST_ONLY_WOULD_CROSS,
    FOK_NOT_FILLABLE,
    UNKNOWN
}

