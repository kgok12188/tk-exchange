package com.tk.protocol.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 单交易对撮合规则（与 match-engine 设计 §10 一致）。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class MarketConfig {

    private String symbol;
    /**
     * 价格定点小数位（与内部 long 刻度、快照互转一致）。
     */
    private int priceScale;
    /**
     * 数量小数位上限（校验用）。
     */
    private Integer qtyScale;
    /**
     * 最小委托量；null 或 0 表示不校验下限。
     */
    private BigDecimal minQty;
    /**
     * 市价单：在 **quote（计价货币）** 维度，若「仍可继续成交的剩余名义」**严格小于**（{@code <}）本值，则撮合引擎 **停止继续撮合**（见 {@code com.tk.match.engine.MarketOrderMatcher}）；**trading-server / 前端** 亦可用于展示完单与解冻语义。
     * <p>
     * {@code null} 或 ≤0 表示不启用该规则。单位与交易对 quote 一致（如 BTC_USDT 即为 USDT）。
     */
    private BigDecimal minTradeQuoteAmount;

    /**
     * 开发/默认：带极小 {@link #minQty} 与 {@link #qtyScale}；数量步长由上层控制，DTO 可不设 step 字段。
     */
    public static MarketConfig defaultFor(String symbol) {
        return MarketConfig.builder()
                .symbol(symbol)
                .priceScale(9)
                .qtyScale(9)
                .minQty(new BigDecimal("0.0000001"))
                .build();
    }


    public BigDecimal getMinTradeQuoteAmount() {
        return minTradeQuoteAmount == null ? new BigDecimal("0.0000001") : minTradeQuoteAmount;
    }

    public int getQtyScale() {
        return qtyScale == null ? 9 : qtyScale;
    }

    public BigDecimal getMinQty() {
        return minQty == null ? new BigDecimal("0.0000001") : minQty;
    }

}
