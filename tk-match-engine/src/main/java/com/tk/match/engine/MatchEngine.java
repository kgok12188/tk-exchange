package com.tk.match.engine;

import com.tk.protocol.dto.*;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-symbol match engine: deterministic state machine, single-threaded.
 * Driven by ordered commands from Aeron Cluster Raft log (onSessionMessage).
 * <p>
 * {@code seq} replaces the old Kafka orderReqOffset: it is the global {@code matchSeq}
 * assigned by {@code MatchClusteredService}, used for BookOrder price-time priority.
 * Raft consensus guarantees each command is processed exactly once — no in-engine dedup needed.
 */
@Getter
public class MatchEngine {

    private final String symbol;
    private final OrderBook book;

    public MatchEngine(String symbol, MarketConfig initialConfig, ArrayStackBookOrder arrayStackBookOrder) {
        this.symbol = symbol;
        this.book = new OrderBook(symbol, initialConfig, arrayStackBookOrder);
    }

    /**
     * Process one command. Returns a MatchResponse if there are trades or finish orders,
     * or null for no-op commands. Callers (ClusteredService) must wrap null → empty MatchResult.
     *
     * @param cmd       decoded command (PUSH_ORDER / CANCEL_ORDER / UPDATE_MARKET)
     * @param seq       global matchSeq from ClusteredService (used for price-time priority in book)
     * @param timestamp cluster timestamp (epoch ms)
     */
    public MatchResponse process(OrderCommand cmd, long seq, long timestamp) {
        if (cmd == null || cmd.getType() == null) {
            return null;
        }
        switch (cmd.getType()) {
            case UPDATE_MARKET:
                return processUpdateMarket(cmd, seq);
            case PUSH_ORDER: {
                OrderPayload push = cmd.getPushPayload();
                if (push == null) {
                    return null;
                }
                TakerRef takerRef = TakerRef.builder()
                        .uid(push.getUid())
                        .orderId(push.getId())
                        .shardId(push.getShardId())
                        .build();
                MatchResult result = book.pushOrder(push, seq, timestamp);
                return MatchResponse.builder()
                        .taker(takerRef)
                        .trades(result.getTrades())
                        .finishOrders(result.getFinishOrders())
                        .build();
            }
            case CANCEL_ORDER: {
                CancelPayload cancel = cmd.getCancelPayload();
                if (cancel == null) {
                    return null;
                }
                TakerRef takerRef = null;
                if (cancel.getUid() != null) {
                    takerRef = TakerRef.builder()
                            .uid(cancel.getUid())
                            .shardId(cancel.getShardId())
                            .orderId(cancel.getOrderId())
                            .build();
                }
                MatchResult result = book.cancelOrder(cancel.getOrderId());
                return MatchResponse.builder()
                        .taker(takerRef)
                        .trades(Collections.emptyList())
                        .finishOrders(result.getFinishOrders())
                        .build();
            }
            default:
                return null;
        }
    }

    private MatchResponse processUpdateMarket(OrderCommand cmd, long seq) {
        MarketUpdatePayload marketUpdatePayload = cmd.getMarketUpdatePayload();
        if (marketUpdatePayload == null || marketUpdatePayload.getMarketConfig() == null) {
            return null;
        }
        if (marketUpdatePayload.getConfigVersion() <= book.getAppliedMarketConfigVersion()) {
            return emptyResponse();
        }
        MarketConfig cfg = marketUpdatePayload.getMarketConfig();
        if (cfg.getSymbol() != null && !cfg.getSymbol().equals(symbol)) {
            return null;
        }
        MarketConfig effective = cfg.getSymbol() == null ? cfg.toBuilder().symbol(symbol).build() : cfg;

        List<BookOrder> nonCompliantOrders = book.findNonCompliantOrders(effective);
        if (!nonCompliantOrders.isEmpty() && !marketUpdatePayload.isForce()) {
            return emptyResponse();
        }
        List<FinishOrder> finishes = new ArrayList<>();
        for (BookOrder nonCompliant : nonCompliantOrders) {
            MatchResult cancelResult = book.cancelOrder(nonCompliant.getOrderId());
            finishes.addAll(cancelResult.getFinishOrders());
        }
        book.applyMarketConfig(effective, marketUpdatePayload.getConfigVersion());
        return MatchResponse.builder()
                .taker(null)
                .trades(Collections.emptyList())
                .finishOrders(finishes)
                .build();
    }

    private static MatchResponse emptyResponse() {
        return MatchResponse.builder()
                .taker(null)
                .trades(Collections.emptyList())
                .finishOrders(Collections.emptyList())
                .build();
    }
}
