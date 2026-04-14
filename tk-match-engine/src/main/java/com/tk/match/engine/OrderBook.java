package com.tk.match.engine;

import com.tk.match.engine.matcher.*;
import com.tk.protocol.dto.*;
import exchange.core2.collections.art.LongAdaptiveRadixTreeMap;
import exchange.core2.collections.art.LongObjConsumer;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

import static com.tk.match.engine.matcher.MatchSupport.finishOrder;

/**
 * High-performance in-memory order book: price-time priority, single-threaded per symbol.
 * Price level index uses {@link LongAdaptiveRadixTreeMap} (long fixed-point tick key).
 * Within each level, FIFO ordering is maintained by {@link PriceLevel}.
 * <p>
 * In the Aeron Cluster model, {@code seq} (= matchSeq from ClusteredService) replaces the old
 * Kafka orderReqOffset for price-time priority ordering of resting orders.
 */
public final class OrderBook {

    @Getter
    private final String symbol;

    @Getter
    private MatchMarketConfig matchMarketConfig;

    /** Last applied config version from UpdateMarketCommand; -1 = never updated. */
    @Getter
    private long appliedMatchMarketConfigVersion = -1L;

    /** Bid side: iterate desc for best bid. */
    private final LongAdaptiveRadixTreeMap<PriceLevel> buySide = new LongAdaptiveRadixTreeMap<>();

    /** Ask side: iterate asc for best ask. */
    private final LongAdaptiveRadixTreeMap<PriceLevel> sellSide = new LongAdaptiveRadixTreeMap<>();

    @Getter
    private final LongAdaptiveRadixTreeMap<BookOrder> ordersById = new LongAdaptiveRadixTreeMap<>();

    @Getter
    private int orderCount = 0;

    private final OrderMatcher LIMIT_MATCHER = new LimitOrderMatcher(this);
    private final OrderMatcher LIMIT_IOC_MATCHER = new LimitIocOrderMatcher(this);
    private final OrderMatcher LIMIT_FOK_MATCHER = new LimitFokOrderMatcher(this);
    private final OrderMatcher MARKET_MATCHER = new MarketOrderMatcher(this);
    private final OrderMatcher LIMIT_MAKER_MATCHER = new LimitMakerOrderMatcher(this);

    private static final int DUPLICATE_ID_WINDOW_SIZE = 4;
    private static final int DUPLICATE_ID_WINDOW_INTERVAL_MILLIS = 1000 * 60 * 15;
    private final OrderIdDeduplicate orderIdDeduplicate;
    private final ArrayStackBookOrder arrayStackBookOrder;

    public OrderBook(String symbol, MatchMarketConfig matchMarketConfig, ArrayStackBookOrder arrayStackBookOrder) {
        if (StringUtils.isEmpty(symbol) || matchMarketConfig == null) {
            throw new IllegalArgumentException("symbol and matchMarketConfig must not be null");
        }
        this.symbol = symbol;
        this.matchMarketConfig = matchMarketConfig;
        this.orderIdDeduplicate = new OrderIdDeduplicate(DUPLICATE_ID_WINDOW_SIZE, DUPLICATE_ID_WINDOW_INTERVAL_MILLIS);
        this.arrayStackBookOrder = arrayStackBookOrder;
    }

    public void applyMatchMarketConfig(MatchMarketConfig cfg, long configVersion) {
        this.matchMarketConfig = cfg;
        this.appliedMatchMarketConfigVersion = configVersion;
    }

    public boolean isEmpty() {
        return orderCount <= 0;
    }

    /**
     * Resting orders that would become non-compliant under the candidate config.
     */
    public List<BookOrder> findNonCompliantOrders(MatchMarketConfig candidate) {
        if (candidate == null) {
            return Collections.emptyList();
        }
        List<BookOrder> all = new ArrayList<>(exportOrders());
        if (all.isEmpty()) {
            return Collections.emptyList();
        }
        if (candidate.getPriceScale() != matchMarketConfig.getPriceScale()) {
            return Collections.unmodifiableList(new ArrayList<>(all));
        }
        List<BookOrder> nonCompliant = new ArrayList<>();
        for (BookOrder eachOrder : all) {
            if (MarketRules.shouldRejectQuantity(eachOrder.getRemainingVolume(), candidate)) {
                nonCompliant.add(eachOrder);
            }
        }
        return nonCompliant;
    }

    /** Lowest ask price tick; null if empty. */
    public Long firstAskPriceTicks() {
        final long[] captured = new long[1];
        final boolean[] found = new boolean[1];
        sellSide.forEach((priceTicks, priceLevel) -> {
            captured[0] = priceTicks;
            found[0] = true;
        }, 1);
        return found[0] ? captured[0] : null;
    }

    /** Highest bid price tick; null if empty. */
    public Long firstBidPriceTicks() {
        final long[] captured = new long[1];
        final boolean[] found = new boolean[1];
        buySide.forEachDesc((priceTicks, priceLevel) -> {
            captured[0] = priceTicks;
            found[0] = true;
        }, 1);
        return found[0] ? captured[0] : null;
    }

    public PriceLevel levelAtAsk(long priceTicks) { return sellSide.get(priceTicks); }
    public PriceLevel levelAtBid(long priceTicks) { return buySide.get(priceTicks); }

    public Long nextAskAfter(long priceTicks) {
        PriceLevel higher = sellSide.getHigherValue(priceTicks);
        return higher == null ? null : higher.getPriceTicks();
    }

    public Long nextBidBelow(long priceTicks) {
        PriceLevel lower = buySide.getLowerValue(priceTicks);
        return lower == null ? null : lower.getPriceTicks();
    }

    public void removeAskLevelIfEmpty(long priceTicks) {
        PriceLevel level = sellSide.get(priceTicks);
        if (level != null && level.isEmpty()) sellSide.remove(priceTicks);
    }

    public void removeBidLevelIfEmpty(long priceTicks) {
        PriceLevel level = buySide.get(priceTicks);
        if (level != null && level.isEmpty()) buySide.remove(priceTicks);
    }

    /**
     * Push a new order into the book. {@code seq} = matchSeq used as price-time priority key.
     */
    public MatchResult pushOrder(OrderPayload payload, long seq, long timestamp) {
        BookOrder takerOrder = arrayStackBookOrder.pop().parse(payload, seq, matchMarketConfig);

        if ((timestamp - payload.getCreateTime()) > matchMarketConfig.getMaxValidTime()
                || payload.getCreateTime() > timestamp) {
            MatchResult result = MatchResult.of(Collections.emptyList(),
                    Collections.singletonList(finishOrder(takerOrder, FinishStatus.REJECT, payload.getVolume(), payload.getAmount(), RejectReason.ORDER_EXPIRED)));
            recycleBookOrder(takerOrder);
            return result;
        }

        if (takerOrder.getOrderId() <= 0) {
            MatchResult result = MatchResult.of(Collections.emptyList(),
                    Collections.singletonList(finishOrder(takerOrder, FinishStatus.REJECT, payload.getVolume(), payload.getAmount(), RejectReason.INVALID_ORDER_ID)));
            recycleBookOrder(takerOrder);
            return result;
        }

        if (orderIdDeduplicate.isDuplicate(takerOrder.getOrderId(), ordersById, timestamp)) {
            MatchResult result = MatchResult.of(Collections.emptyList(),
                    Collections.singletonList(finishOrder(takerOrder, FinishStatus.REJECT, payload.getVolume(), payload.getAmount(), RejectReason.DUPLICATE_ORDER_ID)));
            recycleBookOrder(takerOrder);
            return result;
        }

        TimeInForce timeInForce = TimeInForce.fromWire(payload.getTimeInForce());
        if (isLimitOrder(payload.getPriceType()) && timeInForce == null) {
            MatchResult result = MatchResult.of(Collections.emptyList(),
                    Collections.singletonList(finishOrder(takerOrder, FinishStatus.REJECT, payload.getVolume(), payload.getAmount(), RejectReason.INVALID_TIME_IN_FORCE)));
            recycleBookOrder(takerOrder);
            return result;
        }

        OrderMatcher matcher = selectMatcher(payload.getPriceType(), timeInForce);
        if (matcher == null) {
            MatchResult result = MatchResult.of(Collections.emptyList(),
                    Collections.singletonList(finishOrder(takerOrder, FinishStatus.REJECT, payload.getVolume(), payload.getAmount(), RejectReason.INVALID_PRICE_TYPE)));
            recycleBookOrder(takerOrder);
            return result;
        }
        MatchResult invalid = matcher.validate(takerOrder);
        if (invalid != null) {
            recycleBookOrder(takerOrder);
            return invalid;
        }
        MatchResult result = matcher.match(takerOrder, seq);
        BookOrder inBookOrder = ordersById.get(takerOrder.getOrderId());
        if (inBookOrder != takerOrder) {
            recycleBookOrder(takerOrder);
        }
        return result;
    }

    public MatchResult cancelOrder(Long orderId) {
        BookOrder order = ordersById.get(orderId);
        if (order == null) {
            return emptyResult();
        }
        removeRestingOrder(order);
        FinishOrder fo = MatchSupport.finishOrder(order, FinishStatus.CANCEL, order.getRemainingVolume());
        return MatchResult.of(Collections.emptyList(), Collections.singletonList(fo));
    }

    public void removeRestingOrder(BookOrder order) {
        if (ordersById.get(order.getOrderId()) == null) return;
        ordersById.remove(order.getOrderId());
        orderCount--;
        removeFromBook(order);
        recycleBookOrder(order);
    }

    public void addToBook(BookOrder order) {
        long ticks = order.getPriceTicks();
        LongAdaptiveRadixTreeMap<PriceLevel> sideLevels = order.isSideBuy() ? buySide : sellSide;
        PriceLevel priceLevel = sideLevels.get(ticks);
        if (priceLevel == null) {
            priceLevel = new PriceLevel(ticks);
            sideLevels.put(ticks, priceLevel);
        }
        priceLevel.addLast(order);
        ordersById.put(order.getOrderId(), order);
        orderCount++;
    }

    public void removeFromBook(BookOrder order) {
        long ticks = order.getPriceTicks();
        LongAdaptiveRadixTreeMap<PriceLevel> levelMap = order.isSideBuy() ? buySide : sellSide;
        PriceLevel level = levelMap.get(ticks);
        if (level != null) {
            level.remove(order.getSeq());
            if (level.isEmpty()) levelMap.remove(ticks);
        }
    }

    public Collection<BookOrder> exportOrders() {
        return ordersById.entriesList().stream().map(Map.Entry::getValue).collect(Collectors.toList());
    }

    /** Visit all resting orders without allocating a full list; used for snapshot writing. */
    public void visitBookOrder(LongObjConsumer<BookOrder> consumer) {
        ordersById.forEach(consumer, Integer.MAX_VALUE);
    }

    /**
     * Restore a single resting order during snapshot load.
     * Called by {@code MatchClusteredService.onLoadSnapshot()}.
     */
    public void restoreOrder(long orderId, long uid, int shardId, String side,
                             BigDecimal price, BigDecimal remainingVolume, long seq) {
        long ticks = PriceCodec.encode(price, matchMarketConfig.getPriceScale());
        BookOrder order = new BookOrder(orderId, uid, shardId, side, price, ticks, remainingVolume, seq);
        addToBook(order);
    }

    public void recycleBookOrder(BookOrder order) {
        if (order != null) arrayStackBookOrder.add(order);
    }

    private OrderMatcher selectMatcher(String priceType, TimeInForce timeInForce) {
        if (priceType == null) {
            return null;
        }
        switch (priceType.toUpperCase()) {
            case "MARKET":
                return MARKET_MATCHER;
            case "LIMIT_MAKER":
            case "POST_ONLY":
                return LIMIT_MAKER_MATCHER;
            case "LIMIT":
                return selectLimitMatcher(timeInForce);
            default:
                return LIMIT_MATCHER;
        }
    }

    private OrderMatcher selectLimitMatcher(TimeInForce timeInForce) {
        if (timeInForce == null || timeInForce == TimeInForce.GTC) {
            return LIMIT_MATCHER;
        }
        switch (timeInForce) {
            case IOC:
                return LIMIT_IOC_MATCHER;
            case FOK:
                return LIMIT_FOK_MATCHER;
            default:
                return LIMIT_MATCHER;
        }
    }

    private boolean isLimitOrder(String priceType) {
        return StringUtils.equalsIgnoreCase(priceType, "LIMIT");
    }

    private static MatchResult emptyResult() {
        return MatchResult.of(Collections.emptyList(), Collections.emptyList());
    }
}
