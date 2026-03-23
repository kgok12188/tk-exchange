package com.tk.match.engine.matcher;

import com.tk.match.engine.*;
import com.tk.protocol.dto.FinishOrder;
import com.tk.protocol.dto.FinishStatus;
import com.tk.protocol.dto.MarketConfig;
import com.tk.protocol.dto.RejectReason;
import com.tk.protocol.dto.TradeOrder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 市价单（{@code priceType=MARKET}）：语义为 <strong>IOC</strong>（Immediate-Or-Cancel）。
 * <ul>
 *   <li>仅与对手盘撮合，<strong>永不挂入</strong>本订单簿；</li>
 *   <li>未成交部分以 {@link FinishStatus#PART_CANCEL} 结束（部分成交 + 剩余取消），与 FOK（全成或全拒）不同。</li>
 *   <li>买单：{@link BookOrder#getRemainingAmount()} 为剩余可花 quote；{@link BookOrder#getAmount()} 为原始计价预算；可选 base 上限见 {@link BookOrder#hasMarketBuyBaseCap()}。</li>
 *   <li>卖单：{@link BookOrder#getRemainingVolume()} 为最多卖出 base；可选 {@link BookOrder#getRemainingAmount()} 为累计成交额（quote）上限剩余。</li>
 * </ul>
 * <p>
 * 沿对手盘推进方式与 {@link LimitOrderMatcher} 共用 {@link OppositeSideWalk}。
 */
public final class MarketOrderMatcher implements OrderMatcher {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final OrderBook orderBook;

    public MarketOrderMatcher(OrderBook orderBook) {
        this.orderBook = orderBook;
    }

    @Override
    public MatchResult match(BookOrder takerOrder, long orderReqOffset) {
        List<TradeOrder> trades = new ArrayList<>(4);
        List<FinishOrder> finishes = new ArrayList<>(4);
        int scale = orderBook.getMarketConfig().getPriceScale();

        OppositeSideWalk walk = OppositeSideWalk.forTaker(takerOrder);

        Long oppositeTicks = walk.firstPrice(orderBook);
        while (oppositeTicks != null && marketTakerHasRemaining(takerOrder, orderBook.getMarketConfig(), orderBook, scale)) {
            PriceLevel level = walk.level(orderBook, oppositeTicks);
            if (level == null) {
                break;
            }
            boolean continueToNextPrice = matchMarketAtPriceLevel(orderBook, takerOrder, orderReqOffset, oppositeTicks, scale, orderBook.getMarketConfig(), level, trades, finishes);
            walk.removeLevelIfEmpty(orderBook, oppositeTicks);
            if (!continueToNextPrice) {
                break;
            }
            oppositeTicks = walk.nextOppositePrice(orderBook, oppositeTicks);
        }

        if (marketTakerHasRemaining(takerOrder, orderBook.getMarketConfig(), orderBook, scale)) {
            finishes.add(MatchSupport.finishOrder(takerOrder, FinishStatus.PART_CANCEL, takerOrder.getRemainingVolume(), takerOrder.getRemainingAmount()));
        } else {
            finishes.add(MatchSupport.finishOrder(takerOrder, FinishStatus.COMPLETED, takerOrder.getRemainingVolume(), takerOrder.getRemainingAmount()));
        }
        return MatchResult.of(trades, finishes);
    }

    @Override
    public MatchResult validate(BookOrder takerOrder) {
        MarketConfig mc = orderBook.getMarketConfig();
        if (takerOrder.isSideBuy()) {
            return validateMarketBuy(takerOrder, mc);
        } else {
            return validateMarketSell(takerOrder, mc);
        }
    }

    private static MatchResult validateMarketBuy(BookOrder taker, MarketConfig mc) {
        BigDecimal amount = taker.getRemainingAmount() != null ? taker.getRemainingAmount() : taker.getAmount();
        if (amount == null || amount.compareTo(mc.getMinTradeQuoteAmount()) < 0) {
            BigDecimal leave = taker.getVolume() != null ? taker.getVolume() : ZERO;
            return MatchResult.of(Collections.emptyList(), List.of(MatchSupport.finishOrder(taker, FinishStatus.REJECT, leave, amount, RejectReason.INVALID_NOTIONAL)));
        }
        if (taker.getVolume() != null) {
            if (taker.getVolume().compareTo(mc.getMinQty()) < 0) {
                return MatchResult.of(Collections.emptyList(), List.of(MatchSupport.finishOrder(taker, FinishStatus.REJECT, taker.getVolume(), amount, RejectReason.INVALID_QUANTITY)));
            } else if (taker.getVolume().stripTrailingZeros().scale() > mc.getQtyScale()) {
                return MatchResult.of(Collections.emptyList(), List.of(MatchSupport.finishOrder(taker, FinishStatus.REJECT, taker.getVolume(), amount, RejectReason.INVALID_QUANTITY)));
            }
        }
        return null;
    }

    private static MatchResult validateMarketSell(BookOrder taker, MarketConfig mc) {
        if (taker.getVolume() == null || taker.getVolume().compareTo(mc.getMinQty()) <= 0) {
            BigDecimal leave = taker.getVolume() != null ? taker.getVolume() : ZERO;
            return MatchResult.of(Collections.emptyList(), List.of(MatchSupport.finishOrder(taker, FinishStatus.REJECT, leave, taker.getAmount(), RejectReason.INVALID_QUANTITY)));
        }
        if (taker.getAmount() != null && taker.getAmount().compareTo(mc.getMinTradeQuoteAmount()) < 0) {
            return MatchResult.of(Collections.emptyList(), List.of(MatchSupport.finishOrder(taker, FinishStatus.REJECT, taker.getVolume(), taker.getVolume(), RejectReason.INVALID_NOTIONAL)));
        }
        return null;
    }

    /**
     * 市价 taker 是否仍可继续吃单（含 {@link MarketConfig#getMinTradeQuoteAmount()}：剩余 quote 名义 &lt; 阈值则视为不可再成交）。
     */
    static boolean marketTakerHasRemaining(BookOrder taker, MarketConfig marketConfig, OrderBook book, int priceScale) {
        if (taker.isSideBuy()) {
            if (taker.getRemainingAmount() == null || taker.getRemainingAmount().compareTo(marketConfig.getMinTradeQuoteAmount()) < 0) {
                return false;
            }
            return taker.getVolume() == null || taker.getVolume().compareTo(ZERO) <= 0 || taker.getRemainingVolume().compareTo(ZERO) > 0;
        } else {
            if (taker.getRemainingVolume().compareTo(ZERO) <= 0) {
                return false;
            }
            return taker.getRemainingAmount() == null || taker.getRemainingAmount().compareTo(marketConfig.getMinTradeQuoteAmount()) > 0;
        }
    }

    /**
     * 在当前价位与对手 FIFO 撮合。
     *
     * @return {@code false}：与队首 maker 无法形成合规成交切片（{@code fill <= 0}），责任在 taker 侧预算/规则；maker 已入簿即满足挂单规则，不撤 maker，并<strong>结束整单 IOC</strong>（不再尝试更差价）。{@code true}：可继续外层扫下一价位。
     */
    private static boolean matchMarketAtPriceLevel(OrderBook book, BookOrder taker, long orderReqOffset, long oppositeTicks, int scale, //
                                                   MarketConfig marketConfig, PriceLevel level, List<TradeOrder> trades, List<FinishOrder> finishes) {
        BigDecimal levelPriceDecimal = PriceCodec.decode(oppositeTicks, scale);
        while (!level.isEmpty() && marketTakerHasRemaining(taker, marketConfig, book, scale)) {
            BookOrder makerOrder = level.peekFirst();
            BigDecimal fill = computeMarketFill(taker, makerOrder, levelPriceDecimal, marketConfig);
            if (fill.compareTo(ZERO) <= 0) {
                return false;
            }
            applyMarketFill(book, taker, makerOrder, level, orderReqOffset, oppositeTicks, scale, fill, trades, finishes, levelPriceDecimal);
        }
        return true;
    }

    private static BigDecimal computeMarketFill(BookOrder taker, BookOrder makerOrder, BigDecimal levelPriceDecimal, MarketConfig marketConfig) {
        BigDecimal makerRemainingVolume = makerOrder.getRemainingVolume();
        if (taker.isSideBuy()) {
            BigDecimal spend = taker.getRemainingAmount();
            BigDecimal takerVolume = MarketRules.maxMatchableVolume(spend, levelPriceDecimal, marketConfig);
            BigDecimal fill = makerRemainingVolume.min(takerVolume);
            if (taker.hasMarketBuyBaseCap()) {
                fill = fill.min(taker.getRemainingVolume());
            }
            return MarketRules.allowsMatchTradeVolume(fill, marketConfig) ? fill : ZERO;
        } else {
            BigDecimal fill = makerRemainingVolume.min(taker.getRemainingVolume());
            BigDecimal cap = taker.getRemainingAmount();
            if (cap != null && cap.compareTo(ZERO) > 0) {
                BigDecimal fromCap = MarketRules.maxMatchableVolume(cap, levelPriceDecimal, marketConfig);
                fill = fill.min(fromCap);
            }
            return MarketRules.allowsMatchTradeVolume(fill, marketConfig) ? fill : ZERO;
        }
    }

    private static void applyMarketFill(OrderBook book, BookOrder taker, BookOrder makerOrder, PriceLevel level, long orderReqOffset, long oppositeTicks,//
                                        int scale, BigDecimal fill, List<TradeOrder> trades, List<FinishOrder> finishes, BigDecimal levelPriceDecimal) {
        BigDecimal cost = fill.multiply(levelPriceDecimal);
        if (taker.isSideBuy()) {
            taker.deductRemainingAmount(cost);
            if (taker.hasMarketBuyBaseCap()) {
                taker.deductRemainingVolume(fill);
            }
        } else {
            taker.deductRemainingVolume(fill);
            taker.deductRemainingAmount(cost);
        }
        makerOrder.deductRemainingVolume(fill);
        level.subtractVolume(fill);
        long index = trades.size();
        trades.add(MatchSupport.buildTrade(index, orderReqOffset, oppositeTicks, scale, fill, taker, makerOrder));
        if (makerOrder.getRemainingVolume().compareTo(ZERO) <= 0) {
            book.removeRestingOrder(makerOrder);
            finishes.add(MatchSupport.finishOrder(makerOrder, FinishStatus.COMPLETED, ZERO));
        }
    }
}
