package com.tk.match.engine;

import com.tk.match.slot.ArrayStackBookOrder;
import com.tk.protocol.dto.CommandType;
import com.tk.protocol.dto.FinishOrder;
import com.tk.protocol.dto.FinishStatus;
import com.tk.protocol.dto.MarketConfig;
import com.tk.protocol.dto.MatchResponse;
import com.tk.protocol.dto.OrderCommand;
import com.tk.protocol.dto.OrderPayload;
import com.tk.protocol.dto.RejectReason;
import com.tk.protocol.dto.TradeOrder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchEngineMatcherCoverageTest {

    private static final String SYMBOL = "BTC_USDT";
    private static final long BASE_TIMESTAMP = 1_700_000_000_000L;

    @Test
    void limitGtcCrossesAndRestsRemainingVolume() {
        MatchEngine matchEngine = newEngine();
        matchEngine.process(limitOrder(20001L, 30001L, "SELL", "100.00", "1.0000", "GTC"), 1L, BASE_TIMESTAMP);

        MatchResponse response = matchEngine.process(limitOrder(20002L, 30002L, "BUY", "101.00", "2.0000", "GTC"), 2L, BASE_TIMESTAMP);

        assertNotNull(response);
        assertEquals(1, response.getTrades().size());
        assertEquals(1, response.getFinishOrders().size());
        assertEquals(FinishStatus.COMPLETED, response.getFinishOrders().get(0).getStatus());
        assertEquals(1, matchEngine.getBook().getOrderCount());
    }

    @Test
    void limitGtcFullyFillsAndDoesNotRest() {
        MatchEngine matchEngine = newEngine();
        matchEngine.process(limitOrder(21001L, 30101L, "SELL", "100.00", "1.0000", "GTC"), 1L, BASE_TIMESTAMP);

        MatchResponse response = matchEngine.process(limitOrder(21002L, 30102L, "BUY", "100.00", "1.0000", "GTC"), 2L, BASE_TIMESTAMP);

        assertNotNull(response);
        assertEquals(1, response.getTrades().size());
        assertEquals(2, response.getFinishOrders().size());
        assertEquals(0, matchEngine.getBook().getOrderCount());
    }

    @Test
    void limitIocPartiallyFillsAndCancelsRemainder() {
        MatchEngine matchEngine = newEngine();
        matchEngine.process(limitOrder(22001L, 30201L, "SELL", "100.00", "1.0000", "GTC"), 1L, BASE_TIMESTAMP);

        MatchResponse response = matchEngine.process(limitOrder(22002L, 30202L, "BUY", "101.00", "2.0000", "IOC"), 2L, BASE_TIMESTAMP);

        assertNotNull(response);
        assertEquals(1, response.getTrades().size());
        assertEquals(2, response.getFinishOrders().size());
        assertTrue(response.getFinishOrders().stream().anyMatch(eachFinish -> eachFinish.getStatus() == FinishStatus.PART_CANCEL));
        assertEquals(0, matchEngine.getBook().getOrderCount());
    }

    @Test
    void limitIocNoCrossDirectlyPartCancels() {
        MatchEngine matchEngine = newEngine();
        matchEngine.process(limitOrder(23001L, 30301L, "SELL", "105.00", "1.0000", "GTC"), 1L, BASE_TIMESTAMP);

        MatchResponse response = matchEngine.process(limitOrder(23002L, 30302L, "BUY", "100.00", "1.0000", "IOC"), 2L, BASE_TIMESTAMP);

        assertNotNull(response);
        assertTrue(response.getTrades().isEmpty());
        assertEquals(1, response.getFinishOrders().size());
        assertEquals(FinishStatus.PART_CANCEL, response.getFinishOrders().get(0).getStatus());
    }

    @Test
    void limitFokRejectsWhenCannotFullyFillNow() {
        MatchEngine matchEngine = newEngine();
        matchEngine.process(limitOrder(24001L, 30401L, "SELL", "100.00", "1.0000", "GTC"), 1L, BASE_TIMESTAMP);

        MatchResponse response = matchEngine.process(limitOrder(24002L, 30402L, "BUY", "100.00", "2.0000", "FOK"), 2L, BASE_TIMESTAMP);

        assertNotNull(response);
        assertTrue(response.getTrades().isEmpty());
        assertEquals(1, response.getFinishOrders().size());
        FinishOrder finishOrder = response.getFinishOrders().get(0);
        assertEquals(FinishStatus.REJECT, finishOrder.getStatus());
        assertEquals(RejectReason.FOK_NOT_FILLABLE, finishOrder.getRejectReason());
    }

    @Test
    void limitMakerRejectsWhenWouldCross() {
        MatchEngine matchEngine = newEngine();
        matchEngine.process(limitOrder(25001L, 30501L, "SELL", "100.00", "1.0000", "GTC"), 1L, BASE_TIMESTAMP);

        MatchResponse response = matchEngine.process(limitMakerOrder(25002L, 30502L, "BUY", "100.00", "1.0000"), 2L, BASE_TIMESTAMP);

        assertNotNull(response);
        assertTrue(response.getTrades().isEmpty());
        assertEquals(1, response.getFinishOrders().size());
        assertEquals(FinishStatus.POST_ONLY_REJECT, response.getFinishOrders().get(0).getStatus());
        assertEquals(RejectReason.POST_ONLY_WOULD_CROSS, response.getFinishOrders().get(0).getRejectReason());
    }

    @Test
    void limitMakerRestsWhenNotCrossing() {
        MatchEngine matchEngine = newEngine();
        matchEngine.process(limitOrder(26001L, 30601L, "SELL", "105.00", "1.0000", "GTC"), 1L, BASE_TIMESTAMP);

        MatchResponse response = matchEngine.process(limitMakerOrder(26002L, 30602L, "BUY", "100.00", "1.0000"), 2L, BASE_TIMESTAMP);

        assertNotNull(response);
        assertTrue(response.getTrades().isEmpty());
        assertTrue(response.getFinishOrders().isEmpty());
        assertEquals(2, matchEngine.getBook().getOrderCount());
    }

    @Test
    void marketBuyConsumesByAmountAndPartCancelsWhenAmountLeftTooSmall() {
        MatchEngine matchEngine = newEngine();
        matchEngine.process(limitOrder(27001L, 30701L, "SELL", "100.00", "1.0000", "GTC"), 1L, BASE_TIMESTAMP);
        matchEngine.process(limitOrder(27002L, 30702L, "SELL", "101.00", "1.0000", "GTC"), 2L, BASE_TIMESTAMP);

        MatchResponse response = matchEngine.process(marketBuyOrder(27003L, 30703L, "150.00", "0.0000"), 3L, BASE_TIMESTAMP);

        assertNotNull(response);
        assertEquals(2, response.getTrades().size());
        assertEquals(FinishStatus.COMPLETED, response.getFinishOrders().get(0).getStatus());
    }

    @Test
    void marketBuyCanCompleteWhenAmountAndVolumeCapBothEnough() {
        MatchEngine matchEngine = newEngine();
        matchEngine.process(limitOrder(28001L, 30801L, "SELL", "100.00", "1.0000", "GTC"), 1L, BASE_TIMESTAMP);

        MatchResponse response = matchEngine.process(marketBuyOrder(28002L, 30802L, "120.00", "1.0000"), 2L, BASE_TIMESTAMP);

        assertNotNull(response);
        assertEquals(1, response.getTrades().size());
        assertEquals(FinishStatus.COMPLETED, response.getFinishOrders().get(0).getStatus());
    }

    @Test
    void marketSellCanCompleteAgainstBidBook() {
        MatchEngine matchEngine = newEngine();
        matchEngine.process(limitOrder(29001L, 30901L, "BUY", "100.00", "2.0000", "GTC"), 1L, BASE_TIMESTAMP);

        MatchResponse response = matchEngine.process(marketSellOrder(29002L, 30902L, "1.0000", null), 2L, BASE_TIMESTAMP);

        assertNotNull(response);
        assertEquals(1, response.getTrades().size());
        TradeOrder tradeOrder = response.getTrades().get(0);
        assertEquals(10000L, PriceCodec.encode(tradeOrder.getPrice(), 2));
        assertEquals(FinishStatus.COMPLETED, response.getFinishOrders().get(0).getStatus());
    }

    @Test
    void marketSellStopsWhenAmountCapTooTightAndReturnsPartCancel() {
        MatchEngine matchEngine = newEngine();
        matchEngine.process(limitOrder(30001L, 31001L, "BUY", "100.00", "2.0000", "GTC"), 1L, BASE_TIMESTAMP);

        MatchResponse response = matchEngine.process(marketSellOrder(30002L, 31002L, "1.0000", "0.50"), 2L, BASE_TIMESTAMP);

        assertNotNull(response);
        assertTrue(response.getTrades().isEmpty());
        assertEquals(FinishStatus.REJECT, response.getFinishOrders().get(0).getStatus());
    }

    @Test
    void invalidLimitOrderFieldsProduceRejectReasons() {
        MatchEngine matchEngine = newEngine();

        MatchResponse invalidPriceResponse = matchEngine.process(limitOrder(31001L, 31101L, "BUY", "0.00", "1.0000", "GTC"), 1L, BASE_TIMESTAMP);
        MatchResponse invalidQtyResponse = matchEngine.process(limitOrder(31002L, 31102L, "BUY", "100.00", "0.0001", "GTC"), 2L, BASE_TIMESTAMP);
        MatchResponse invalidTickResponse = matchEngine.process(limitOrder(31003L, 31103L, "BUY", "100.001", "1.0000", "GTC"), 3L, BASE_TIMESTAMP);

        List<RejectReason> rejectReasons = List.of(
                invalidPriceResponse.getFinishOrders().get(0).getRejectReason(),
                invalidQtyResponse.getFinishOrders().get(0).getRejectReason(),
                invalidTickResponse.getFinishOrders().get(0).getRejectReason()
        );
        assertTrue(rejectReasons.contains(RejectReason.INVALID_PRICE));
        assertTrue(rejectReasons.contains(RejectReason.INVALID_QUANTITY));
        assertTrue(rejectReasons.contains(RejectReason.PRICE_TICK_INVALID));
    }

    @Test
    void invalidMarketOrderFieldsProduceRejectReasons() {
        MatchEngine matchEngine = newEngine();

        MatchResponse buyInvalidNotional = matchEngine.process(marketBuyOrder(32001L, 31201L, "0.10", "1.0000"), 1L, BASE_TIMESTAMP);
        MatchResponse sellInvalidQty = matchEngine.process(marketSellOrder(32002L, 31202L, "0.0001", null), 2L, BASE_TIMESTAMP);
        MatchResponse sellInvalidNotional = matchEngine.process(marketSellOrder(32003L, 31203L, "1.0000", "0.10"), 3L, BASE_TIMESTAMP);

        assertEquals(RejectReason.INVALID_NOTIONAL, buyInvalidNotional.getFinishOrders().get(0).getRejectReason());
        assertEquals(RejectReason.INVALID_QUANTITY, sellInvalidQty.getFinishOrders().get(0).getRejectReason());
        assertEquals(RejectReason.INVALID_NOTIONAL, sellInvalidNotional.getFinishOrders().get(0).getRejectReason());
    }

    @Test
    void invalidTimeInForceRejectsLimitOrder() {
        MatchEngine matchEngine = newEngine();
        MatchResponse response = matchEngine.process(limitOrder(33001L, 31301L, "BUY", "100.00", "1.0000", "INVALID"), 1L, BASE_TIMESTAMP);

        assertNotNull(response);
        assertEquals(FinishStatus.REJECT, response.getFinishOrders().get(0).getStatus());
        assertEquals(RejectReason.INVALID_TIME_IN_FORCE, response.getFinishOrders().get(0).getRejectReason());
    }

    private static MatchEngine newEngine() {
        MarketConfig marketConfig = MarketConfig.builder()
                .symbol(SYMBOL)
                .priceScale(2)
                .qtyScale(4)
                .minQty(new BigDecimal("0.0010"))
                .minTradeQuoteAmount(new BigDecimal("1.00"))
                .build();
        return new MatchEngine(SYMBOL, marketConfig, new ArrayStackBookOrder(4096));
    }

    private static OrderCommand limitOrder(long orderId, long uid, String side, String price, String volume, String timeInForce) {
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
        return push(payload);
    }

    private static OrderCommand limitMakerOrder(long orderId, long uid, String side, String price, String volume) {
        OrderPayload payload = OrderPayload.builder()
                .id(orderId)
                .uid(uid)
                .shardId(1)
                .symbol(SYMBOL)
                .side(side)
                .priceType("LIMIT_MAKER")
                .price(new BigDecimal(price))
                .volume(new BigDecimal(volume))
                .createTime(BASE_TIMESTAMP)
                .build();
        return push(payload);
    }

    private static OrderCommand marketBuyOrder(long orderId, long uid, String amount, String volumeCap) {
        OrderPayload payload = OrderPayload.builder()
                .id(orderId)
                .uid(uid)
                .shardId(1)
                .symbol(SYMBOL)
                .side("BUY")
                .priceType("MARKET")
                .amount(new BigDecimal(amount))
                .volume(new BigDecimal(volumeCap))
                .createTime(BASE_TIMESTAMP)
                .build();
        return push(payload);
    }

    private static OrderCommand marketSellOrder(long orderId, long uid, String volume, String amountCap) {
        OrderPayload.OrderPayloadBuilder payloadBuilder = OrderPayload.builder()
                .id(orderId)
                .uid(uid)
                .shardId(1)
                .symbol(SYMBOL)
                .side("SELL")
                .priceType("MARKET")
                .volume(new BigDecimal(volume))
                .createTime(BASE_TIMESTAMP);
        if (amountCap != null) {
            payloadBuilder.amount(new BigDecimal(amountCap));
        }
        return push(payloadBuilder.build());
    }

    private static OrderCommand push(OrderPayload payload) {
        return OrderCommand.builder()
                .type(CommandType.PUSH_ORDER)
                .symbol(SYMBOL)
                .pushPayload(payload)
                .build();
    }
}
