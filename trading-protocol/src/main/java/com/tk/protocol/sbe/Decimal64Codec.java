package com.tk.protocol.sbe;

import com.tk.protocol.sbe.generated.Decimal64Decoder;
import com.tk.protocol.sbe.generated.Decimal64Encoder;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * BigDecimal ↔ SBE Decimal64 (mantissa int64 + exponent int8) conversion.
 * <p>
 * Encoding: value = mantissa × 10^exponent
 * Example: 64523.75 → mantissa=6452375, exponent=-2
 * <p>
 * NULL sentinel: mantissa=Long.MIN_VALUE encodes null/absent value.
 */
public final class Decimal64Codec {

    public static final long NULL_MANTISSA = Long.MIN_VALUE;
    public static final byte NULL_EXPONENT = Byte.MIN_VALUE;

    private Decimal64Codec() {
    }

    /**
     * Encode BigDecimal into SBE Decimal64Encoder. Null value uses sentinel.
     */
    public static void encode(BigDecimal value, Decimal64Encoder encoder) {
        if (value == null) {
            encoder.mantissa(NULL_MANTISSA);
            encoder.exponent(NULL_EXPONENT);
            return;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        int scale = stripped.scale();
        long mantissa = stripped.unscaledValue().longValueExact();
        encoder.mantissa(mantissa);
        encoder.exponent((byte) (-scale));
    }

    /**
     * Decode SBE Decimal64Decoder into BigDecimal. Returns null for sentinel values.
     */
    public static BigDecimal decode(Decimal64Decoder decoder) {
        long mantissa = decoder.mantissa();
        byte exponent = decoder.exponent();
        if (mantissa == NULL_MANTISSA) {
            return null;
        }
        if (mantissa == 0) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(mantissa, MathContext.UNLIMITED).scaleByPowerOfTen(exponent);
    }

    /**
     * Encode BigDecimal into raw mantissa/exponent fields (for direct buffer access).
     */
    public static long mantissa(BigDecimal value) {
        if (value == null) return NULL_MANTISSA;
        return value.stripTrailingZeros().unscaledValue().longValueExact();
    }

    public static byte exponent(BigDecimal value) {
        if (value == null) return NULL_EXPONENT;
        return (byte) (-value.stripTrailingZeros().scale());
    }

    /**
     * Decode raw mantissa/exponent into BigDecimal.
     */
    public static BigDecimal decode(long mantissa, byte exponent) {
        if (mantissa == NULL_MANTISSA) return null;
        if (mantissa == 0) return BigDecimal.ZERO;
        return new BigDecimal(mantissa, MathContext.UNLIMITED).scaleByPowerOfTen(exponent);
    }
}
