package com.tk.protocol.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 单交易对撮合规则，由 match-engine Raft 共识层使用（design.md §13.2）。
 * <p>
 * 通过 {@code OpenMarketCommand}（templateId=4）随上币指令一次性写入 Raft log；
 * 后续 {@code UpdateMarketCommand}（templateId=3）按 symbolId 更新规则。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class MatchMarketConfig {

    private int symbolId;

    /**
     * 交易对名称，如 "BTC_USDT"。仅在 OpenMarketCommand 和快照中传输，订单流只用 symbolId。
     */
    private String symbolName;

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
     * 市价单 quote 维度最小名义金额。{@code null} 或 ≤0 表示不启用。
     */
    private BigDecimal minTradeQuoteAmount;

    public static MatchMarketConfig defaultFor(String symbol, int symbolId) {
        return MatchMarketConfig.builder()
                .symbolId(symbolId)
                .symbolName(symbol)
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

    public long getMaxValidTime() {
        return 10_000;
    }

}
