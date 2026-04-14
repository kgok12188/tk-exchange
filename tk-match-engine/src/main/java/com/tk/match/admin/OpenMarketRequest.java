package com.tk.match.admin;

import java.math.BigDecimal;

public class OpenMarketRequest {

    private int symbolId;
    private String symbolName;
    private int priceScale;
    private int qtyScale;
    private BigDecimal minQty;
    private BigDecimal minTradeQuoteAmount;
    private long configVersion;

    public int getSymbolId() { return symbolId; }
    public void setSymbolId(int symbolId) { this.symbolId = symbolId; }

    public String getSymbolName() { return symbolName; }
    public void setSymbolName(String symbolName) { this.symbolName = symbolName; }

    public int getPriceScale() { return priceScale; }
    public void setPriceScale(int priceScale) { this.priceScale = priceScale; }

    public int getQtyScale() { return qtyScale; }
    public void setQtyScale(int qtyScale) { this.qtyScale = qtyScale; }

    public BigDecimal getMinQty() { return minQty; }
    public void setMinQty(BigDecimal minQty) { this.minQty = minQty; }

    public BigDecimal getMinTradeQuoteAmount() { return minTradeQuoteAmount; }
    public void setMinTradeQuoteAmount(BigDecimal minTradeQuoteAmount) {
        this.minTradeQuoteAmount = minTradeQuoteAmount;
    }

    public long getConfigVersion() { return configVersion; }
    public void setConfigVersion(long configVersion) { this.configVersion = configVersion; }
}
