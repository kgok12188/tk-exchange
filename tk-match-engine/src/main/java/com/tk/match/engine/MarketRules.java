package com.tk.match.engine;

import com.tk.protocol.dto.MatchMarketConfig;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 入簿前委托参数校验：最小委托量、数量小数位、限价价格小数位。
 * <p>
 * 数量<strong>步长网格</strong>（lot step）由网关/API 等上层约束；撮合引擎<strong>不校验</strong> step。
 */
public final class MarketRules {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private MarketRules() {
    }


    /**
     * 是否应<strong>拒绝</strong>该委托数量（与字面「合规」相反：{@code true} 表示违反规则，对应 {@link OrderBook#pushOrder} 的 REJECT）。
     * <p>
     * 规则：低于 {@link MatchMarketConfig#getMinQty()}（若配置为正）、或小数位数超过 {@link MatchMarketConfig#getQtyScale()}（若 {@code qtyScale >= 0}）。
     */
    public static boolean shouldRejectQuantity(BigDecimal volume, MatchMarketConfig config) {
        if (volume == null || volume.compareTo(ZERO) <= 0 || config == null) {
            return true;
        }
        if (config.getMinQty() != null && config.getMinQty().signum() > 0 && volume.compareTo(config.getMinQty()) < 0) {
            return true;
        }
        if (config.getQtyScale() >= 0) {
            int volumeScale = volume.stripTrailingZeros().scale();
            return volumeScale > config.getQtyScale();
        }
        return false;
    }

    /**
     * 限价单价格小数位不超过 priceScale（市价可为 null）。
     */
    public static boolean isPriceCompliant(BigDecimal price, MatchMarketConfig config) {
        if (price == null) {
            return true;
        }
        if (config == null) {
            return false;
        }
        return price.stripTrailingZeros().scale() <= config.getPriceScale();
    }

    /**
     * 在给定单价下，quote 预算理论上最多可买到的 base（先按 16 位除法向下取整，再按 {@link MatchMarketConfig#getQtyScale()} 向下取整）。
     * 若结果低于 {@link MatchMarketConfig#getMinQty()}（配置为正时），返回 0（无法按规则形成有效成交切片）。
     */
    public static BigDecimal maxMatchableVolume(BigDecimal amount, BigDecimal price, MatchMarketConfig config) {
        if (amount == null || price == null || amount.compareTo(ZERO) <= 0 || price.compareTo(ZERO) <= 0) {
            return ZERO;
        }
        BigDecimal rawMaxBase = amount.divide(price, 18, RoundingMode.DOWN);
        if (config != null && config.getQtyScale() >= 0) {
            rawMaxBase = rawMaxBase.setScale(config.getQtyScale(), RoundingMode.DOWN);
        }
        if (config != null && config.getMinQty() != null && config.getMinQty().signum() > 0 && rawMaxBase.compareTo(config.getMinQty()) < 0) {
            return ZERO;
        }
        return rawMaxBase;
    }

    /**
     * 单笔撮合成交量是否允许（正数且不满足 {@link #shouldRejectQuantity} 的拒绝条件）。
     */
    public static boolean allowsMatchTradeVolume(BigDecimal volume, MatchMarketConfig config) {
        if (volume == null || volume.compareTo(ZERO) <= 0) {
            return false;
        }
        if (config == null) {
            return true;
        }
        return !shouldRejectQuantity(volume, config);
    }

}
