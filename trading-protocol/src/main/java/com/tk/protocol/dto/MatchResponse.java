package com.tk.protocol.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Match-engine output: taker ref, list of trades, list of orders that reached terminal state.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MatchResponse {
    private TakerRef taker;
    private List<TradeOrder> trades = new ArrayList<>();
    private List<FinishOrder> finishOrders = new ArrayList<>();
    private long offset;
}
