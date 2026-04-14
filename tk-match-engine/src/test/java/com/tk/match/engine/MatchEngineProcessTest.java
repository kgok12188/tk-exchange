package com.tk.match.engine;

import com.tk.protocol.dto.CancelPayload;
import com.tk.protocol.dto.CommandType;
import com.tk.protocol.dto.FinishStatus;
import com.tk.protocol.dto.MatchMarketConfig;
import com.tk.protocol.dto.MatchResponse;
import com.tk.protocol.dto.OrderCommand;
import com.tk.protocol.dto.OrderPayload;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchEngineProcessTest {

    private static final String SYMBOL = "BTC_USDT";
    private static final long BASE_TIMESTAMP = 1_700_000_000_000L;

    @Test
    void processAlwaysHandlesCommandRegardlessOfSeq() {
        MatchEngine matchEngine = newEngine();

        OrderCommand firstCommand = pushLimitOrderCommand(10001L, 2001L, "BUY", "100.00", "1.0000", "GTC");
        MatchResponse firstResponse = matchEngine.process(firstCommand, 10L, BASE_TIMESTAMP);
        assertNotNull(firstResponse);

        OrderCommand secondCommand = pushLimitOrderCommand(10002L, 2002L, "BUY", "101.00", "1.0000", "GTC");
        MatchResponse sameSeqResponse = matchEngine.process(secondCommand, 10L, BASE_TIMESTAMP);
        assertNotNull(sameSeqResponse, "Raft guarantees exactly-once delivery; engine does not dedup by seq");
    }

    @Test
    void processReturnsNullForNullCommandOrNullType() {
        MatchEngine matchEngine = newEngine();

        MatchResponse nullCommandResponse = matchEngine.process(null, 1L, BASE_TIMESTAMP);
        assertNull(nullCommandResponse);

        OrderCommand noTypeCommand = OrderCommand.builder().symbol(SYMBOL).build();
        MatchResponse noTypeResponse = matchEngine.process(noTypeCommand, 2L, BASE_TIMESTAMP);
        assertNull(noTypeResponse);
    }

    @Test
    void processCancelOrderReturnsCancelFinishWhenOrderExists() {
        MatchEngine matchEngine = newEngine();
        MatchResponse putResponse = matchEngine.process(pushLimitOrderCommand(11001L, 3001L, "BUY", "100.00", "1.0000", "GTC"), 1L, BASE_TIMESTAMP);
        assertNotNull(putResponse);

        OrderCommand cancelCommand = OrderCommand.builder()
                .type(CommandType.CANCEL_ORDER)
                .symbol(SYMBOL)
                .cancelPayload(CancelPayload.builder().orderId(11001L).uid(3001L).shardId(1).build())
                .build();
        MatchResponse cancelResponse = matchEngine.process(cancelCommand, 2L, BASE_TIMESTAMP);

        assertNotNull(cancelResponse);
        assertEquals(1, cancelResponse.getFinishOrders().size());
        assertEquals(FinishStatus.CANCEL, cancelResponse.getFinishOrders().get(0).getStatus());
    }

    @Test
    void processCancelOrderReturnsEmptyWhenOrderMissing() {
        MatchEngine matchEngine = newEngine();
        OrderCommand cancelCommand = OrderCommand.builder()
                .type(CommandType.CANCEL_ORDER)
                .symbol(SYMBOL)
                .cancelPayload(CancelPayload.builder().orderId(99999L).uid(8L).shardId(1).build())
                .build();

        MatchResponse cancelResponse = matchEngine.process(cancelCommand, 1L, BASE_TIMESTAMP);

        assertNotNull(cancelResponse);
        assertTrue(cancelResponse.getFinishOrders().isEmpty());
        assertTrue(cancelResponse.getTrades().isEmpty());
    }

    // ── applyConfig tests ────────────────────────────────────────────────────

    @Test
    void applyConfigIgnoresOlderConfigVersion() {
        MatchEngine matchEngine = newEngine();

        MatchResponse firstResponse = matchEngine.applyConfig(configWith("0.0100", 2), 5L, false);
        assertNotNull(firstResponse);

        MatchResponse staleResponse = matchEngine.applyConfig(configWith("0.1000", 2), 4L, true);
        assertNotNull(staleResponse);
        assertTrue(staleResponse.getFinishOrders().isEmpty(), "Stale version should be ignored");
    }

    @Test
    void applyConfigForceFalseDoesNotApplyWhenBookHasNonCompliantOrders() {
        MatchEngine matchEngine = newEngine();
        matchEngine.process(pushLimitOrderCommand(12001L, 9001L, "BUY", "100.00", "0.0050", "GTC"), 1L, BASE_TIMESTAMP);

        MatchResponse response = matchEngine.applyConfig(configWith("0.0100", 2), 2L, false);

        assertNotNull(response);
        assertTrue(response.getFinishOrders().isEmpty());
        assertEquals(new BigDecimal("0.0010"), matchEngine.getBook().getMatchMarketConfig().getMinQty());
    }

    @Test
    void applyConfigForceTrueCancelsNonCompliantOrdersAndAppliesConfig() {
        MatchEngine matchEngine = newEngine();
        matchEngine.process(pushLimitOrderCommand(13001L, 9101L, "BUY", "100.00", "0.0050", "GTC"), 1L, BASE_TIMESTAMP);
        matchEngine.process(pushLimitOrderCommand(13002L, 9102L, "SELL", "200.00", "0.0060", "GTC"), 2L, BASE_TIMESTAMP);

        MatchResponse response = matchEngine.applyConfig(configWith("0.0100", 2), 3L, true);

        assertNotNull(response);
        assertEquals(2, response.getFinishOrders().size());
        assertEquals(0, matchEngine.getBook().getOrderCount());
        assertEquals(new BigDecimal("0.0100"), matchEngine.getBook().getMatchMarketConfig().getMinQty());
    }

    // ── close tests ──────────────────────────────────────────────────────────

    @Test
    void closeWithForceCancelsAllRestingOrders() {
        MatchEngine matchEngine = newEngine();
        matchEngine.process(pushLimitOrderCommand(14001L, 9201L, "BUY", "100.00", "1.0000", "GTC"), 1L, BASE_TIMESTAMP);
        matchEngine.process(pushLimitOrderCommand(14002L, 9202L, "SELL", "200.00", "1.0000", "GTC"), 2L, BASE_TIMESTAMP);

        MatchResponse response = matchEngine.close(true);

        assertNotNull(response);
        assertEquals(2, response.getFinishOrders().size());
        assertTrue(matchEngine.isClosed());
        assertEquals(0, matchEngine.getBook().getOrderCount());
    }

    @Test
    void closeWithoutForceMarkesEngineClosed() {
        MatchEngine matchEngine = newEngine();

        MatchResponse response = matchEngine.close(false);

        assertNotNull(response);
        assertTrue(response.getFinishOrders().isEmpty());
        assertTrue(matchEngine.isClosed());
    }

    @Test
    void closeIsIdempotent() {
        MatchEngine matchEngine = newEngine();
        matchEngine.close(false);
        assertFalse(matchEngine.close(false).getFinishOrders().size() > 0);
        assertTrue(matchEngine.isClosed());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static MatchEngine newEngine() {
        MatchMarketConfig marketConfig = MatchMarketConfig.builder()
                .symbolId(1)
                .symbolName(SYMBOL)
                .priceScale(2)
                .qtyScale(4)
                .minQty(new BigDecimal("0.0010"))
                .minTradeQuoteAmount(new BigDecimal("1.00"))
                .build();
        return new MatchEngine(SYMBOL, marketConfig, new ArrayStackBookOrder(2048));
    }

    private static MatchMarketConfig configWith(String minQty, int priceScale) {
        return MatchMarketConfig.builder()
                .symbolId(1)
                .symbolName(SYMBOL)
                .priceScale(priceScale)
                .qtyScale(4)
                .minQty(new BigDecimal(minQty))
                .minTradeQuoteAmount(new BigDecimal("1.00"))
                .build();
    }

    private static OrderCommand pushLimitOrderCommand(long orderId, long uid, String side,
                                                      String price, String volume, String timeInForce) {
        OrderPayload payload = OrderPayload.builder()
                .id(orderId)
                .uid(uid)
                .shardId(1)
                .symbol(SYMBOL)
                .side(side)
                .priceType("LIMIT")
                .timeInForce(timeInForce)
                .price(new BigDecimal(price))
                .volume(new BigDecimal(volume))
                .createTime(BASE_TIMESTAMP)
                .build();
        return OrderCommand.builder()
                .type(CommandType.PUSH_ORDER)
                .symbol(SYMBOL)
                .pushPayload(payload)
                .build();
    }
}
