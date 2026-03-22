package com.tk.match.engine;

import com.tk.protocol.dto.*;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-symbol match engine: state machine driven by OrderCommand, output MatchResponse.
 * Uses only protocol DTOs; single-threaded per symbol.
 * <p>
 * orderReqOffset in TradeOrder (protocol field matchId) is set to the order_req Kafka offset; index is the 0-based trade sequence as the taker eats.
 */
@Getter
public class MatchEngine {

    private final String symbol;
    private final OrderBook book;

    public MatchEngine(String symbol, MarketConfig initialConfig) {
        this.symbol = symbol;
        this.book = new OrderBook(symbol, initialConfig);
    }

    /**
     * Process one command in order; returns response if there are trades or finish orders.
     *
     * @param orderReqOffset Kafka partition offset of the order_req record (used as orderReqOffset for all trades from this command).
     */
    public MatchResponse process(OrderCommand cmd, long orderReqOffset, long timestamp) {
        if (orderReqOffset <= book.getReqOffset()) {
            return null;
        }
        book.setReqOffset(orderReqOffset);
        if (cmd == null || cmd.getType() == null) {
            return null;
        }
        List<TradeOrder> trades = new ArrayList<>(4);
        List<FinishOrder> finishOrders = new ArrayList<>(4);
        TakerRef takerRef = null;
        switch (cmd.getType()) {
            case UPDATE_MARKET:
                return processUpdateMarket(cmd, orderReqOffset);
            case PUSH_ORDER: {
                OrderPayload push = cmd.getPushPayload();
                if (push == null) {
                    return null;
                }
                takerRef = TakerRef.builder().uid(push.getUid()).orderId(push.getId()).shardId(push.getShardId()).build();
                MatchResult result = book.pushOrder(push, orderReqOffset,timestamp);
                trades.addAll(result.getTrades());
                finishOrders.addAll(result.getFinishOrders());
                break;
            }
            case CANCEL_ORDER: {
                CancelPayload cancel = cmd.getCancelPayload();
                if (cancel == null) {
                    return null;
                }
                if (cancel.getUid() != null) {
                    takerRef = TakerRef.builder().uid(cancel.getUid()).shardId(cancel.getShardId()).orderId(cancel.getOrderId()).build();
                }
                MatchResult result = book.cancelOrder(cancel.getOrderId());
                finishOrders.addAll(result.getFinishOrders());
                break;
            }
            default:
                return null;
        }
        return MatchResponse.builder().taker(takerRef).trades(trades).offset(orderReqOffset).finishOrders(finishOrders).build();
    }

    private MatchResponse processUpdateMarket(OrderCommand cmd, long orderReqOffset) {
        MarketUpdatePayload marketUpdatePayload = cmd.getMarketUpdatePayload();
        if (marketUpdatePayload == null || marketUpdatePayload.getMarketConfig() == null) {
            return null;
        }
        if (marketUpdatePayload.getConfigVersion() <= book.getAppliedMarketConfigVersion()) {
            return MatchResponse.builder().taker(null).trades(Collections.emptyList()).offset(orderReqOffset).finishOrders(Collections.emptyList()).build();
        }
        MarketConfig cfg = marketUpdatePayload.getMarketConfig();

        if (cfg.getSymbol() != null && !cfg.getSymbol().equals(symbol)) {
            return null;
        }
        MarketConfig effective = cfg.getSymbol() == null ? cfg.toBuilder().symbol(symbol).build() : cfg;

        List<BookOrder> nonCompliantOrders = book.findNonCompliantOrders(effective);
        if (!nonCompliantOrders.isEmpty() && !marketUpdatePayload.isForce()) {
            return MatchResponse.builder().taker(null).trades(Collections.emptyList()).offset(orderReqOffset).finishOrders(Collections.emptyList()).build();
        }
        List<FinishOrder> finishes = new ArrayList<>();
        if (!nonCompliantOrders.isEmpty()) {
            for (BookOrder nonCompliant : nonCompliantOrders) {
                MatchResult cancelResult = book.cancelOrder(nonCompliant.getOrderId());
                finishes.addAll(cancelResult.getFinishOrders());
            }
        }
        book.applyMarketConfig(effective, marketUpdatePayload.getConfigVersion());
        return MatchResponse.builder().taker(null).trades(Collections.emptyList()).offset(orderReqOffset).finishOrders(finishes).build();
    }
}
