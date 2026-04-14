package com.tk.protocol.sbe;

import com.tk.protocol.dto.*;
import com.tk.protocol.sbe.generated.*;
import com.tk.protocol.sbe.generated.TimeInForce;
import org.agrona.DirectBuffer;

import java.math.BigDecimal;
import java.nio.ByteOrder;

/**
 * Decodes SBE-encoded ingress messages from DirectBuffer into internal protocol DTOs.
 * <p>
 * templateId dispatch:
 * <ul>
 *   <li>1 → PushOrderCommand  → OrderCommand(PUSH_ORDER)</li>
 *   <li>2 → CancelOrderCommand → OrderCommand(CANCEL_ORDER)</li>
 *   <li>3 → UpdateMarketCommand → OrderCommand(UPDATE_MARKET)</li>
 * </ul>
 */
public final class SbeDecoder {

    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final PushOrderCommandDecoder pushDecoder = new PushOrderCommandDecoder();
    private final CancelOrderCommandDecoder cancelDecoder = new CancelOrderCommandDecoder();
    private final UpdateMarketCommandDecoder updateMarketDecoder = new UpdateMarketCommandDecoder();

    /**
     * Decode one SBE message from {@code buffer} at {@code offset}.
     *
     * @return decoded OrderCommand, or null if templateId is unknown
     */
    public OrderCommand decode(DirectBuffer buffer, int offset, int length) {
        headerDecoder.wrap(buffer, offset);
        int templateId = headerDecoder.templateId();
        int headerLength = headerDecoder.encodedLength();
        int actingBlockLength = headerDecoder.blockLength();
        int actingVersion = headerDecoder.version();
        int bodyOffset = offset + headerLength;

        return switch (templateId) {
            case PushOrderCommandDecoder.TEMPLATE_ID ->
                    decodePushOrder(buffer, bodyOffset, actingBlockLength, actingVersion);
            case CancelOrderCommandDecoder.TEMPLATE_ID ->
                    decodeCancelOrder(buffer, bodyOffset, actingBlockLength, actingVersion);
            case UpdateMarketCommandDecoder.TEMPLATE_ID ->
                    decodeUpdateMarket(buffer, bodyOffset, actingBlockLength, actingVersion);
            default -> null;
        };
    }

    /**
     * Fast-path: extract templateId from the SBE message header.
     * Used by MatchClusteredService to dispatch to the correct handler.
     */
    public int extractTemplateId(DirectBuffer buffer, int offset) {
        headerDecoder.wrap(buffer, offset);
        return headerDecoder.templateId();
    }

    /**
     * Fast-path: extract symbolId (always the first field = first 4 bytes after header) without
     * full decode. Works for all command message types (templateId 1–5).
     */
    public int extractSymbolId(DirectBuffer buffer, int offset) {
        headerDecoder.wrap(buffer, offset);
        int bodyOffset = offset + headerDecoder.encodedLength();
        return buffer.getInt(bodyOffset, ByteOrder.LITTLE_ENDIAN);
    }

    // ── Message decoders ─────────────────────────────────────────────────────

    private OrderCommand decodePushOrder(DirectBuffer buffer, int offset,
                                         int blockLength, int version) {
        pushDecoder.wrap(buffer, offset, blockLength, version);

        BigDecimal price = Decimal64Codec.decode(pushDecoder.price());
        BigDecimal volume = Decimal64Codec.decode(pushDecoder.volume());
        BigDecimal amount = Decimal64Codec.decode(pushDecoder.amount());

        OrderPayload payload = OrderPayload.builder()
                .id(pushDecoder.orderId())
                .uid(pushDecoder.uid())
                .shardId(pushDecoder.shardId())
                .marketId(pushDecoder.marketId())
                .side(pushDecoder.side() == Side.BUY ? "BUY" : "SELL")
                .priceType(toProtocolPriceType(pushDecoder.priceType()))
                .timeInForce(toProtocolTimeInForce(pushDecoder.timeInForce()))
                .price(price)
                .volume(volume)
                .amount(amount)
                .createTime(pushDecoder.createTime())
                .build();

        return OrderCommand.builder()
                .type(CommandType.PUSH_ORDER)
                .pushPayload(payload)
                .build();
    }

    private OrderCommand decodeCancelOrder(DirectBuffer buffer, int offset,
                                           int blockLength, int version) {
        cancelDecoder.wrap(buffer, offset, blockLength, version);

        CancelPayload payload = CancelPayload.builder()
                .orderId(cancelDecoder.orderId())
                .uid(cancelDecoder.uid())
                .shardId(cancelDecoder.shardId())
                .build();

        return OrderCommand.builder()
                .type(CommandType.CANCEL_ORDER)
                .cancelPayload(payload)
                .build();
    }

    /**
     * UpdateMarketCommand (templateId=3) is handled directly by MatchClusteredService
     * using its own decoder; SbeDecoder returns null so the caller knows to skip the
     * generic order-flow path.
     */
    private OrderCommand decodeUpdateMarket(DirectBuffer buffer, int offset,
                                            int blockLength, int version) {
        return null;
    }

    // ── Enum converters (SBE generated → DTO string) ─────────────────────────

    private static String toProtocolPriceType(PriceType priceType) {
        if (priceType == null) return null;
        return switch (priceType) {
            case LIMIT -> "LIMIT";
            case MARKET -> "MARKET";
            case LIMIT_MAKER -> "LIMIT_MAKER";
            default -> null;
        };
    }

    private static String toProtocolTimeInForce(TimeInForce tif) {
        if (tif == null) return null;
        return switch (tif) {
            case GTC -> "GTC";
            case IOC -> "IOC";
            case FOK -> "FOK";
            case NULL_VAL -> null;
        };
    }
}
