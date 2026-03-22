package com.tk.protocol.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Payload for PUSH_ORDER command. Required: id, uid, symbol/marketId, side, priceType, and sizing fields
 * as described below.
 * <p>
 * <b>交易对语义（如 BTC_USDT）</b>：标的资产 <b>base</b> = BTC，计价货币 <b>quote</b> = USDT。
 * 撮合引擎内部委托数量 {@link #volume} 与成交 {@link TradeOrder#getVolume()} 均为 <b>base 数量</b>（BTC）；
 * {@link #amount} 表示 <b>quote 名义</b>（USDT），用于按价折算为 base。
 * <p>
 * <b>业务侧锁仓（由 trading-server / 账户实现，非 match-engine）</b>：
 * <ul>
 *   <li>市价 <b>买入</b>：用户持有并锁定 <b>quote（USDT）</b>，额度与 {@code amount} 或折算逻辑一致；</li>
 *   <li>市价 <b>卖出</b>：用户持有并锁定 <b>base（BTC）</b>，数量与 {@link #volume} 一致。</li>
 * </ul>
 * <p>
 * <b>市价单（{@code priceType=MARKET}）</b>：IOC，不入簿；见 {@code doc/市价单与交易对资产语义.md}。
 * <ul>
 *   <li><b>BUY</b>：{@link #amount} <b>必传</b>——最多成交金额（quote，如 USDT）；{@link #volume} <b>可选</b>，{@code >0} 时表示最多成交数量（base）上限，{@code null/0} 表示不按 base 上限截断（仅受 quote 预算约束）；</li>
 *   <li><b>SELL</b>：{@link #volume} <b>必传</b>——最多卖出 base；{@link #amount} <b>可选</b>，{@code >0} 时表示累计成交额（quote）上限，{@code null/0} 表示不限制；{@code price} 可为 null。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderPayload {
    private Long id;
    private Long uid;
    private int shardId;
    private String symbol;
    private Long marketId;
    /** BUY / SELL */
    private String side;
    /** LIMIT, MARKET, LIMIT_MAKER (post-only) */
    private String priceType;
    /** 限价必填；市价可为 null */
    private BigDecimal price;
    /**
     * <b>base</b> 数量（如 BTC）。限价必填其一或配合 amount；<b>市价卖单必传</b>；市价买单可选为「最多成交 base」。
     */
    private BigDecimal volume;
    /**
     * <b>quote</b> 名义（如 USDT）。限价可与 {@link #price} 折算 base；<b>市价买单必传</b>（最多成交金额）；市价卖单可选为「累计成交额上限」。
     */
    private BigDecimal amount;
}
