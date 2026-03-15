package com.tk.protocol.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Single instruction on order_req_(symbol). Type discriminates payload.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCommand {
    private CommandType type;
    private String symbol;
    private OrderPayload pushPayload;   // non-null when type == PUSH_ORDER
    private CancelPayload cancelPayload; // non-null when type == CANCEL_ORDER
    private Integer schemaVersion;       // optional, for protocol evolution
}
