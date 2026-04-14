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
 * Admin commands (OpenMarket / CloseMarket / UpdateMarket) are handled by
 * {@code MatchClusteredService} directly; this engine only processes order flow.
 */
@Getter
public class MatchEngine {

    private final String symbol;
    private final OrderBook book;
    private boolean closed;

    public MatchEngine(String symbol, MatchMarketConfig initialConfig, ArrayStackBookOrder arrayStackBookOrder) {
        this.symbol = symbol;
        this.book = new OrderBook(symbol, initialConfig, arrayStackBookOrder);
        this.closed = false;
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * Process one order command (PUSH_ORDER or CANCEL_ORDER).
     * Returns a MatchResponse, or null for unrecognized commands.
     * Callers (ClusteredService) must wrap null → empty MatchResult.
     *
     * @param cmd       decoded command
     * @param seq       global matchSeq from ClusteredService
     * @param timestamp cluster timestamp (epoch ms)
     */
    public MatchResponse process(OrderCommand cmd, long seq, long timestamp) {
        if (cmd == null || cmd.getType() == null) {
            return null;
        }
        switch (cmd.getType()) {
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

    /**
     * Apply a new market configuration update (called by ClusteredService for UpdateMarketCommand).
     * If {@code force=true}, non-compliant resting orders are cancelled first; if {@code force=false}
     * and non-compliant orders exist, the update is silently rejected (idempotent empty response).
     *
     * @param cfg           new market config
     * @param configVersion must be greater than the currently applied version; older versions are ignored
     * @param force         true = cancel non-compliant orders before applying
     * @return empty response (trades=[], finishOrders=cancelled orders if force=true)
     */
    public MatchResponse applyConfig(MatchMarketConfig cfg, long configVersion, boolean force) {
        if (configVersion <= book.getAppliedMatchMarketConfigVersion()) {
            return emptyResponse();
        }
        List<BookOrder> nonCompliant = book.findNonCompliantOrders(cfg);
        if (!nonCompliant.isEmpty() && !force) {
            return emptyResponse();
        }
        List<FinishOrder> finishes = new ArrayList<>();
        for (BookOrder order : nonCompliant) {
            MatchResult cancelResult = book.cancelOrder(order.getOrderId());
            finishes.addAll(cancelResult.getFinishOrders());
        }
        book.applyMatchMarketConfig(cfg, configVersion);
        return MatchResponse.builder()
                .taker(null)
                .trades(Collections.emptyList())
                .finishOrders(finishes)
                .build();
    }

    /**
     * Close this market (called by ClusteredService for CloseMarketCommand).
     * If {@code force=true}, all resting orders are cancelled before marking closed.
     * If {@code force=false} and orders remain, does nothing (caller should emit empty MatchResult).
     *
     * @return MatchResponse containing cancelled orders (may be empty)
     */
    public MatchResponse close(boolean force) {
        if (closed) {
            return emptyResponse();
        }
        List<FinishOrder> finishes = new ArrayList<>();
        if (force) {
            List<BookOrder> allOrders = new ArrayList<>(book.exportOrders());
            for (BookOrder order : allOrders) {
                MatchResult cancelResult = book.cancelOrder(order.getOrderId());
                finishes.addAll(cancelResult.getFinishOrders());
            }
        }
        closed = true;
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
