package com.tk.match.engine;

import com.tk.protocol.dto.FinishOrder;
import com.tk.protocol.dto.FinishStatus;
import com.tk.protocol.dto.OrderPayload;
import com.tk.protocol.dto.TradeOrder;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * High-performance in-memory order book: price-time priority, single-threaded per symbol.
 * Delegates LIMIT / MARKET / LIMIT_MAKER behaviour to {@link LimitOrderMatcher}, {@link MarketOrderMatcher}, {@link LimitMakerOrderMatcher}.
 */
public final class OrderBook {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private static final OrderMatcher LIMIT_MATCHER = new LimitOrderMatcher();
    private static final OrderMatcher MARKET_MATCHER = new MarketOrderMatcher();
    private static final OrderMatcher LIMIT_MAKER_MATCHER = new LimitMakerOrderMatcher();

    @Getter
    private String matchResultTopic;

    /**
     * Buy: best first = highest price, then FIFO.
     */
    private final TreeMap<BigDecimal, PriceLevel> buySide;
    /**
     * Sell: best first = lowest price, then FIFO.
     */
    private final TreeMap<BigDecimal, PriceLevel> sellSide;
    private final Map<Long, BookOrder> ordersById;
    @Setter
    @Getter
    private long reqOffset;

    @Getter
    private volatile long masterReqOffset;

    /**
     * 比对服务报告的 order_req 对齐进度（exclusive 语义与 {@link com.tk.match.compare.MatchResultSlaveFileQueue#replay} 的 min 一致）。
     * 由 {@link com.tk.match.slot.event.ComparedEvent} 经 Disruptor 更新；切主时与 Kafka 尾部取较大值作为文件队列补发起始。
     */
    @Setter
    @Getter
    private transient long comparedFileOffset;

    /**
     * 从节点 slave Chronicle 上已比对对齐到的文档索引（供切主补发 {@link com.tk.match.compare.MatchResultSlaveFileQueue#replay} 的 startIndexHint）；-1 表示未设置。
     */
    @Setter
    @Getter
    private transient long comparedFileQueueStartIndex = -1L;

    @Getter
    @Setter
    private transient long snapshotOffset;

    /**
     * 仅当 {@code orderReqOffset} 大于当前 masterOffset 时更新，避免回退。线程安全。
     */
    public void updateMasterReqOffsetIfGreater(long masterReqOffset) {
        if (this.masterReqOffset < masterReqOffset) {
            this.masterReqOffset = masterReqOffset;
        }
    }


    public OrderBook(String symbol) {
        if (StringUtils.isEmpty(symbol)) {
            throw new IllegalArgumentException("symbol is empty");
        }
        this.buySide = new TreeMap<>(Comparator.reverseOrder());
        this.sellSide = new TreeMap<>();
        this.ordersById = new HashMap<>(64);
        this.matchResultTopic = "match_result_" + symbol;
    }

    /**
     * Process PUSH_ORDER: dispatch by priceType to LIMIT / MARKET / LIMIT_MAKER matcher.
     *
     * @param orderReqOffset Kafka partition offset of the order_req message (written to TradeOrder.matchId for all trades from this command).
     */
    public MatchResult pushOrder(OrderPayload payload, long orderReqOffset) {
        if (payload == null) return emptyResult();
        BigDecimal volume = effectiveVolume(payload);
        if (volume == null || volume.compareTo(ZERO) <= 0) return emptyResult();

        BookOrder order = toBookOrder(payload, volume, orderReqOffset);
        ordersById.put(order.getOrderId(), order);

        OrderMatcher matcher = selectMatcher(payload.getPriceType());
        return matcher.match(this, order, orderReqOffset);
    }

    private static OrderMatcher selectMatcher(String priceType) {
        if (priceType == null) return LIMIT_MATCHER;
        return switch (priceType.toUpperCase()) {
            case "MARKET" -> MARKET_MATCHER;
            case "LIMIT_MAKER", "POST_ONLY" -> LIMIT_MAKER_MATCHER;
            default -> LIMIT_MATCHER;
        };
    }

    /**
     * Process CANCEL_ORDER: remove from book and return FinishOrder.
     */
    public MatchResult cancelOrder(Long orderId) {
        BookOrder order = ordersById.remove(orderId);
        if (order == null) return emptyResult();
        removeFromBook(order);
        FinishOrder fo = finishOrder(order, FinishStatus.CANCEL, order.getRemainingVolume());
        return MatchResult.of(Collections.emptyList(), List.of(fo));
    }

    TreeMap<BigDecimal, PriceLevel> getBuySide() {
        return buySide;
    }

    TreeMap<BigDecimal, PriceLevel> getSellSide() {
        return sellSide;
    }

    Map<Long, BookOrder> getOrdersById() {
        return ordersById;
    }

    /**
     * Best bid (highest buy price), or null if buy side empty.
     */
    BigDecimal getBestBid() {
        return buySide.isEmpty() ? null : buySide.firstKey();
    }

    /**
     * Best ask (lowest sell price), or null if sell side empty.
     */
    BigDecimal getBestAsk() {
        return sellSide.isEmpty() ? null : sellSide.firstKey();
    }

    void addToBook(BookOrder order) {
        TreeMap<BigDecimal, PriceLevel> book = order.isSideBuy() ? buySide : sellSide;
        book.computeIfAbsent(order.getPrice(), k -> new PriceLevel()).addLast(order);
    }

    void removeFromBook(BookOrder order) {
        TreeMap<BigDecimal, PriceLevel> levelMap = order.isSideBuy() ? buySide : sellSide;
        PriceLevel level = levelMap.get(order.getPrice());
        if (level != null) {
            level.remove(order.getSeq());
            if (level.isEmpty()) levelMap.remove(order.getPrice());
        }
    }

    /**
     * Export all resting orders for snapshot (buy + sell, no guaranteed order).
     * Caller should sort by seq when restoring.
     */
    public Collection<BookOrder> exportOrders() {
        return ordersById.values();
    }

    /**
     * Restore one order into the book without matching (for startup recovery).
     */
    public void restoreOrder(long orderId, long uid, int shardId, String side, BigDecimal price, BigDecimal remainingVolume, long seq) {
        BookOrder order = new BookOrder(orderId, uid, shardId, side, price, remainingVolume, seq);
        ordersById.put(order.getOrderId(), order);
        addToBook(order);
    }

    private static BigDecimal effectiveVolume(OrderPayload p) {
        if (p.getVolume() != null && p.getVolume().compareTo(ZERO) > 0) return p.getVolume();
        if (p.getAmount() != null && p.getPrice() != null && p.getPrice().compareTo(ZERO) > 0) {
            return p.getAmount().divide(p.getPrice(), 16, RoundingMode.DOWN);
        }
        return null;
    }

    private BookOrder toBookOrder(OrderPayload p, BigDecimal volume, long orderReqOffset) {
        return new BookOrder(p.getId(), p.getUid(), p.getShardId(), p.getSide(), p.getPrice(), volume, orderReqOffset);
    }

    static TradeOrder buildTrade(long index, long orderReqOffset, BigDecimal price, BigDecimal volume, BookOrder taker, BookOrder maker) {
        boolean takerBuy = taker.isSideBuy();
        return TradeOrder.builder().index(index).orderReqOffset(orderReqOffset).price(price).volume(volume)
                .buyUid(takerBuy ? taker.getUid() : maker.getUid())
                .sellUid(takerBuy ? maker.getUid() : taker.getUid())
                .buyOrderId(takerBuy ? taker.getOrderId() : maker.getOrderId())
                .sellOrderId(takerBuy ? maker.getOrderId() : taker.getOrderId())
                .takerOrderId(taker.getOrderId()).takerUid(taker.getUid())
                .buyShardId(takerBuy ? taker.getShardId() : maker.getShardId())
                .sellShardId(takerBuy ? maker.getShardId() : taker.getShardId()).build();
    }

    static FinishOrder finishOrder(BookOrder bookOrder, FinishStatus status, BigDecimal leaveVolume) {
        return FinishOrder.builder().uid(bookOrder.getUid()).orderId(bookOrder.getOrderId()).shardId(bookOrder.getShardId()).status(status)
                .leaveVolume(leaveVolume).leaveAmount(leaveVolume != null && bookOrder.getPrice() != null && leaveVolume.signum() > 0 ? leaveVolume.multiply(bookOrder.getPrice()) : null).build();
    }

    private static MatchResult emptyResult() {
        return MatchResult.of(Collections.emptyList(), Collections.emptyList());
    }
}
