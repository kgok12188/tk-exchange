package com.tk.match.engine;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tk.protocol.dto.MarketConfig;
import com.tk.protocol.dto.OrderPayload;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * In-book order representation: immutable identity/price, mutable remaining volume.
 * Used only inside OrderBook; not part of protocol.
 * <p>
 * 市价单：{@link #volume}/{@link #remainingVolume} 为 base；{@link #amount}/{@link #remainingAmount} 为计价货币（quote）。
 * 限价单：通常仅使用 volume/remainingVolume 与 price；{@code amount}/{@code remainingAmount} 为 null。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BookOrder {

    private long orderId;
    private Long uid;
    private int shardId;
    private String side;
    private BigDecimal price;
    /**
     * 与 {@link OrderBook} 的 {@code priceScale} 一致的定点价格刻度；用于 ART 索引与比较（LIMIT 单必填，MARKET 等可为 0）。
     */
    @EqualsAndHashCode.Exclude
    @JsonIgnore
    private transient long priceTicks;
    private BigDecimal remainingVolume;
    /**
     * 订单原始数量（base）；市价卖为最多卖出 base；市价买若有 base 上限则为该上限初值，无上限时为 0。
     */
    private BigDecimal volume;
    private long seq;
    private boolean sideBuy;

    /**
     * 原始计价货币数量（与协议 {@code OrderPayload.amount} 对齐）：市价买为最多花费 quote；市价卖若设累计成交额上限则为该上限初值；否则 null。
     */
    @EqualsAndHashCode.Exclude
    @JsonIgnore
    private BigDecimal amount;
    /**
     * 剩余可成交计价货币：市价买为剩余可花 quote；市价卖为剩余累计成交额上限（未设上限时为 null）。
     */
    @EqualsAndHashCode.Exclude
    @JsonIgnore
    private BigDecimal remainingAmount;

    /**
     * 同价位 FIFO 队列侵入式双向链表（仅 {@link PriceLevel} 使用）；不参与快照 JSON 与 equals/hashCode。
     */
    @EqualsAndHashCode.Exclude
    @JsonIgnore
    private BookOrder prevInPriceLevel;
    @EqualsAndHashCode.Exclude
    @JsonIgnore
    private BookOrder nextInPriceLevel;

    public BookOrder() {

    }

    public BookOrder(OrderPayload payload, long orderReqOffset, MarketConfig marketConfig) {
        this.orderId = payload.getId();
        this.uid = payload.getUid();
        this.shardId = payload.getShardId();
        this.side = payload.getSide();
        this.price = payload.getPrice();
        this.priceTicks = payload.getPrice() == null ? 0L : PriceCodec.encode(payload.getPrice(), marketConfig.getPriceScale());
        this.remainingVolume = payload.getVolume() == null ? BigDecimal.ZERO : payload.getVolume();
        this.volume = remainingVolume;
        this.seq = orderReqOffset;
        this.sideBuy = "BUY".equalsIgnoreCase(side);
        this.amount = payload.getAmount() == null ? null : payload.getAmount();
        this.remainingAmount = payload.getAmount() == null ? null : payload.getAmount();
    }

    public BookOrder(Long orderId, Long uid, int shardId, String side, BigDecimal price, long priceTicks, BigDecimal remainingVolume, long seq) {
        this.orderId = orderId;
        this.uid = uid;
        this.shardId = shardId;
        this.side = side;
        this.price = price;
        this.priceTicks = priceTicks;
        this.remainingVolume = remainingVolume;
        this.volume = remainingVolume;
        this.seq = seq;
        this.sideBuy = "BUY".equalsIgnoreCase(side);
    }

    public BigDecimal getRemainingVolume() {
        return remainingVolume == null ? BigDecimal.ZERO : remainingVolume;
    }

    /**
     * 从剩余 base 数量中扣减 {@code deduction}；{@code deduction} 为 null 或 ≤0 时不操作。
     */
    public void deductRemainingVolume(BigDecimal deduction) {
        if (deduction == null || deduction.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal current = remainingVolume == null ? BigDecimal.ZERO : remainingVolume;
        this.remainingVolume = current.subtract(deduction);
    }

    /**
     * 从剩余计价数量中扣减 {@code deduction}；无计价剩余（{@link #remainingAmount} 为 null）或 {@code deduction} 为 null/≤0 时不操作。
     */
    public void deductRemainingAmount(BigDecimal deduction) {
        if (remainingAmount == null || deduction == null || deduction.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        this.remainingAmount = remainingAmount.subtract(deduction);
    }

    /**
     * 市价买单是否设有 base 上限（派生：{@link #getAmount()} != null 且 {@link #getVolume()} &gt; 0；与入参 {@code volume &gt; 0} 一致）。
     */
    public boolean hasMarketBuyBaseCap() {
        return isSideBuy() && amount != null && volume != null && volume.compareTo(BigDecimal.ZERO) > 0;
    }

}
