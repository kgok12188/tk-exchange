package com.tk.match.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 协议层 {@link BigDecimal} 价格与订单簿内部 long 刻度互转（与 exchange-core 类定点思路一致）。
 */
public final class PriceCodec {

    private PriceCodec() {
    }

    public static long encode(BigDecimal price, int scale) {
        if (price == null) {
            throw new IllegalArgumentException("price can't be null");
        }
        return price.movePointRight(scale).setScale(0, RoundingMode.DOWN).longValue();
    }

    public static BigDecimal decode(long ticks, int scale) {
        return BigDecimal.valueOf(ticks).movePointLeft(scale);
    }

}
