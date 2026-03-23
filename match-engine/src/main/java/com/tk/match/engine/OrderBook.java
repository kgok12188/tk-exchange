package com.tk.match.engine;

import com.tk.match.engine.matcher.*;
import com.tk.match.snapshot.SnapshotLoadResult;
import com.tk.match.snapshot.SnapshotMetadata;
import com.tk.protocol.dto.*;
import exchange.core2.collections.art.LongAdaptiveRadixTreeMap;
import exchange.core2.collections.art.LongObjConsumer;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.util.*;

import static com.tk.match.engine.matcher.MatchSupport.finishOrder;

/**
 * High-performance in-memory order book: price-time priority, single-threaded per symbol.
 * 价格档位索引使用 {@link LongAdaptiveRadixTreeMap}（long 定点刻度键，借鉴 exchange-core）；档内仍为 FIFO {@link PriceLevel}。
 */
public final class OrderBook {

    @Getter
    private String matchResultTopic;

    @Getter
    private final String symbol;

    /**
     * 当前生效的撮合规则；{@link #applyMarketConfig} / 快照加载时更新。
     */
    @Getter
    private MarketConfig marketConfig;

    /**
     * 已应用的 {@link com.tk.protocol.dto.MarketUpdatePayload#getConfigVersion()}；-1 表示未经过 UPDATE_MARKET。
     */
    @Getter
    private long appliedMarketConfigVersion = -1L;

    /**
     * Bid：价格从高到低遍历用 {@link LongAdaptiveRadixTreeMap#forEachDesc} / {@link LongAdaptiveRadixTreeMap#getLowerValue(long)}。
     */
    private final LongAdaptiveRadixTreeMap<PriceLevel> buySide = new LongAdaptiveRadixTreeMap<>();
    /**
     * Ask：价格从低到高用 {@link LongAdaptiveRadixTreeMap#forEach} / {@link LongAdaptiveRadixTreeMap#getHigherValue(long)}。
     */
    private final LongAdaptiveRadixTreeMap<PriceLevel> sellSide = new LongAdaptiveRadixTreeMap<>();
    @Getter
    private final LongAdaptiveRadixTreeMap<BookOrder> ordersById = new LongAdaptiveRadixTreeMap<>();

    @Getter
    private transient int orderCount = 0;

    private final OrderMatcher LIMIT_MATCHER = new LimitOrderMatcher(this);
    private final OrderMatcher LIMIT_IOC_MATCHER = new LimitIocOrderMatcher(this);
    private final OrderMatcher LIMIT_FOK_MATCHER = new LimitFokOrderMatcher(this);
    private final OrderMatcher MARKET_MATCHER = new MarketOrderMatcher(this);
    private final OrderMatcher LIMIT_MAKER_MATCHER = new LimitMakerOrderMatcher(this);


    @Setter
    @Getter
    private long reqOffset;

    @Getter
    private volatile long masterReqOffset;

    @Setter
    @Getter
    private transient long comparedFileOffset;

    @Setter
    @Getter
    private transient long comparedFileQueueStartIndex = -1L;

    @Getter
    @Setter
    private transient long snapshotOffset;

    private static final int DUPLICATE_ID_WINDOW_SIZE = 4;
    private static final int DUPLICATE_ID_WINDOW_INTERVAL_MILLIS = 1000 * 60 * 15;
    private final transient OrderIdDeduplicate orderIdDeduplicate;

    public OrderBook(String symbol, MarketConfig marketConfig) {
        if (StringUtils.isEmpty(symbol) || marketConfig == null) {
            throw new IllegalArgumentException("symbol is empty");
        }
        this.symbol = symbol;
        this.marketConfig = marketConfig;
        this.matchResultTopic = "match_result_" + symbol;
        orderIdDeduplicate = new OrderIdDeduplicate(DUPLICATE_ID_WINDOW_SIZE, DUPLICATE_ID_WINDOW_INTERVAL_MILLIS);
    }

    /**
     * 从快照元数据恢复规则
     */
    public void loadFromSnapshot(SnapshotLoadResult snapshotLoadResult, long configVersion) throws Exception {
        if (snapshotLoadResult.snapshotMetadata().getMarketConfig() != null) {
            this.marketConfig = snapshotLoadResult.snapshotMetadata().getMarketConfig();
        }
        SnapshotMetadata snapshotMetadata = snapshotLoadResult.snapshotMetadata();
        orderIdDeduplicate.loadFromSnapshot(snapshotMetadata.getNavigable(), symbol);
        this.appliedMarketConfigVersion = configVersion;
    }

    /**
     * 应用通过 {@code UPDATE_MARKET} 校验后的新配置。
     */
    public void applyMarketConfig(MarketConfig cfg, long configVersion) {
        this.marketConfig = cfg;
        this.appliedMarketConfigVersion = configVersion;
    }

    public boolean isEmpty() {
        return orderCount <= 0;
    }

    /**
     * 相对候选规则仍不合规的存量挂单（含 priceScale 变更时全部挂单）。
     */
    public List<BookOrder> findNonCompliantOrders(MarketConfig candidate) {
        if (candidate == null) {
            return List.of();
        }
        List<BookOrder> all = new ArrayList<>(exportOrders());
        if (all.isEmpty()) {
            return List.of();
        }
        if (candidate.getPriceScale() != marketConfig.getPriceScale()) {
            return List.copyOf(all);
        }
        List<BookOrder> nonCompliant = new ArrayList<>();
        for (BookOrder eachOrder : all) {
            if (MarketRules.shouldRejectQuantity(eachOrder.getRemainingVolume(), candidate)) {
                nonCompliant.add(eachOrder);
            }
        }
        return nonCompliant;
    }


    public void updateMasterReqOffsetIfGreater(long masterReqOffset) {
        if (this.masterReqOffset < masterReqOffset) {
            this.masterReqOffset = masterReqOffset;
        }
    }

    /**
     * 最低卖价刻度；无档位返回 null。
     */
    public Long firstAskPriceTicks() {
        final long[] capturedPriceTick = new long[1];
        final boolean[] foundFirst = new boolean[1];
        sellSide.forEach((priceTicks, priceLevel) -> {
            capturedPriceTick[0] = priceTicks;
            foundFirst[0] = true;
        }, 1);
        return foundFirst[0] ? capturedPriceTick[0] : null;
    }

    /**
     * 最高买价刻度；无档位返回 null。
     */
    public Long firstBidPriceTicks() {
        final long[] capturedPriceTick = new long[1];
        final boolean[] foundFirst = new boolean[1];
        buySide.forEachDesc((priceTicks, priceLevel) -> {
            capturedPriceTick[0] = priceTicks;
            foundFirst[0] = true;
        }, 1);
        return foundFirst[0] ? capturedPriceTick[0] : null;
    }

    public PriceLevel levelAtAsk(long priceTicks) {
        return sellSide.get(priceTicks);
    }

    public PriceLevel levelAtBid(long priceTicks) {
        return buySide.get(priceTicks);
    }

    /**
     * 严格高于 {@code priceTicks} 的下一卖价档位（用于吃单遍历）。
     */
    public Long nextAskAfter(long priceTicks) {
        PriceLevel higherAskLevel = sellSide.getHigherValue(priceTicks);
        return higherAskLevel == null ? null : higherAskLevel.getPriceTicks();
    }

    /**
     * 严格低于 {@code priceTicks} 的下一买价档位。
     */
    public Long nextBidBelow(long priceTicks) {
        PriceLevel lowerBidLevel = buySide.getLowerValue(priceTicks);
        return lowerBidLevel == null ? null : lowerBidLevel.getPriceTicks();
    }

    public void removeAskLevelIfEmpty(long priceTicks) {
        PriceLevel askLevel = sellSide.get(priceTicks);
        if (askLevel != null && askLevel.isEmpty()) {
            sellSide.remove(priceTicks);
        }
    }

    public void removeBidLevelIfEmpty(long priceTicks) {
        PriceLevel bidLevel = buySide.get(priceTicks);
        if (bidLevel != null && bidLevel.isEmpty()) {
            buySide.remove(priceTicks);
        }
    }

    public MatchResult pushOrder(OrderPayload payload, long orderReqOffset, long timestamp) {

        BookOrder takerOrder = new BookOrder(payload, orderReqOffset, marketConfig);

        if ((timestamp - payload.getCreateTime()) > marketConfig.getMaxValidTime() || payload.getCreateTime() > timestamp) {
            return MatchResult.of(Collections.emptyList(), List.of(finishOrder(takerOrder, FinishStatus.REJECT, payload.getVolume(), payload.getAmount(), RejectReason.ORDER_EXPIRED)));
        }

        if (takerOrder.getOrderId() <= 0) {
            return MatchResult.of(Collections.emptyList(), List.of(finishOrder(takerOrder, FinishStatus.REJECT, payload.getVolume(), payload.getAmount(), RejectReason.INVALID_ORDER_ID)));
        }
        if (orderIdDeduplicate.isDuplicate(takerOrder.getOrderId(), ordersById, timestamp)) {
            return MatchResult.of(Collections.emptyList(), List.of(finishOrder(takerOrder, FinishStatus.REJECT, payload.getVolume(), payload.getAmount(), RejectReason.DUPLICATE_ORDER_ID)));
        }

        TimeInForce timeInForce = TimeInForce.fromWire(payload.getTimeInForce());
        if (isLimitOrder(payload.getPriceType()) && payload.getTimeInForce() != null && timeInForce == null) {
            return MatchResult.of(Collections.emptyList(), List.of(
                    finishOrder(takerOrder, FinishStatus.REJECT, payload.getVolume(), payload.getAmount(), RejectReason.INVALID_TIME_IN_FORCE)
            ));
        }

        OrderMatcher matcher = selectMatcher(payload.getPriceType(), timeInForce);
        if (matcher == null) {
            return MatchResult.of(Collections.emptyList(), List.of(finishOrder(takerOrder, FinishStatus.REJECT, payload.getVolume(), payload.getAmount(), RejectReason.INVALID_PRICE_TYPE)));
        }
        MatchResult invalid = matcher.validate(takerOrder);
        if (invalid != null) {
            return invalid;
        }
        return matcher.match(takerOrder, orderReqOffset);
    }

    private OrderMatcher selectMatcher(String priceType, TimeInForce timeInForce) {
        if (priceType == null) return null;
        return switch (priceType.toUpperCase()) {
            case "MARKET" -> MARKET_MATCHER;
            case "LIMIT_MAKER", "POST_ONLY" -> LIMIT_MAKER_MATCHER;
            case "LIMIT" -> selectLimitMatcher(timeInForce);
            default -> LIMIT_MATCHER;
        };
    }

    private OrderMatcher selectLimitMatcher(TimeInForce timeInForce) {
        if (timeInForce == null || timeInForce == TimeInForce.GTC) {
            return LIMIT_MATCHER;
        }
        return switch (timeInForce) {
            case IOC -> LIMIT_IOC_MATCHER;
            case FOK -> LIMIT_FOK_MATCHER;
            default -> LIMIT_MATCHER;
        };
    }

    private boolean isLimitOrder(String priceType) {
        return priceType != null && "LIMIT".equalsIgnoreCase(priceType);
    }

    public MatchResult cancelOrder(Long orderId) {
        BookOrder order = ordersById.get(orderId);
        if (order == null) {
            return emptyResult();
        }
        removeRestingOrder(order);
        FinishOrder fo = MatchSupport.finishOrder(order, FinishStatus.CANCEL, order.getRemainingVolume());
        return MatchResult.of(Collections.emptyList(), List.of(fo));
    }

    /**
     * 从 id 索引与价位 FIFO 中移除已在簿上的订单，并维护 {@link #orderCount}。
     * 用于撤单、maker 完全成交等路径；与 {@link #addToBook(BookOrder)} 成对。
     */
    public void removeRestingOrder(BookOrder order) {
        if (ordersById.get(order.getOrderId()) == null) {
            return;
        }
        ordersById.remove(order.getOrderId());
        orderCount--;
        removeFromBook(order);
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
            if (level.isEmpty()) {
                levelMap.remove(ticks);
            }
        }
    }

    public Collection<BookOrder> exportOrders() {
        return ordersById.entriesList().stream().map(Map.Entry::getValue).toList();
    }

    /**
     * 按订单 ID 树遍历当前簿内挂单（单线程语义下使用）；用于快照写盘等场景，避免 {@link #exportOrders()} 分配整表集合。
     */
    public void visitBookOrder(LongObjConsumer<BookOrder> consumer) {
        ordersById.forEach(consumer, Integer.MAX_VALUE);
    }

    public ArrayDeque<Roaring64NavigableMapWrapper> getNavigableMapWrappers() {
        return orderIdDeduplicate.recentOrderIdWindows();
    }

    public void restoreOrder(long orderId, long uid, int shardId, String side, BigDecimal price, BigDecimal remainingVolume, long seq) {
        long ticks = PriceCodec.encode(price, marketConfig.getPriceScale());
        BookOrder order = new BookOrder(orderId, uid, shardId, side, price, ticks, remainingVolume, seq);
        addToBook(order);
    }

    private static MatchResult emptyResult() {
        return MatchResult.of(Collections.emptyList(), Collections.emptyList());
    }

}
