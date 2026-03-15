package com.tk.protocol.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-user settlement package: finish orders and tickets for one user.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradingSettle {
    private Long uid;
    private int shardId;
    private List<FinishOrder> finishOrders = new ArrayList<>();
    private List<Ticket> tickets = new ArrayList<>();
}
