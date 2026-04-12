package com.tk.protocol.sbe;

import com.tk.protocol.dto.FinishOrder;
import com.tk.protocol.dto.TradeOrder;
import com.tk.protocol.sbe.generated.*;
import org.agrona.MutableDirectBuffer;

import java.math.BigDecimal;
import java.util.List;

/**
 * Encodes internal MatchResult data into SBE MatchResult messages written to a MutableDirectBuffer.
 * <p>
 * Strictly 1:1: every command produces one MatchResult. Empty trades/finishOrders → groups with count=0.
 */
public final class SbeEncoder {

    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final MatchResultEncoder matchResultEncoder = new MatchResultEncoder();

    private final SnapshotHeaderEncoder snapshotHeaderEncoder = new SnapshotHeaderEncoder();
    private final SnapshotSymbolHeaderEncoder symbolHeaderEncoder = new SnapshotSymbolHeaderEncoder();
    private final SnapshotBookOrderEncoder bookOrderEncoder = new SnapshotBookOrderEncoder();

    /**
     * Encode one MatchResult into {@code buffer} at {@code offset}.
     *
     * @param matchSeq     global match sequence number
     * @param symbolId     wire symbol id
     * @param takerUid     taker uid (0 if no taker, e.g. UPDATE_MARKET)
     * @param takerOrderId taker order id (0 if no taker)
     * @param takerShardId taker shard id (0 if no taker)
     * @param trades       list of TradeOrder (may be empty or null)
     * @param finishOrders list of FinishOrder (may be empty or null)
     * @param buffer       target buffer
     * @param offset       write start offset
     * @return total bytes written (header + body)
     */
    public int encodeMatchResult(
            long matchSeq, int symbolId,
            long takerUid, long takerOrderId, int takerShardId,
            List<TradeOrder> trades,
            List<FinishOrder> finishOrders,
            MutableDirectBuffer buffer, int offset) {

        headerEncoder.wrap(buffer, offset)
                .blockLength(MatchResultEncoder.BLOCK_LENGTH)
                .templateId(MatchResultEncoder.TEMPLATE_ID)
                .schemaId(MatchResultEncoder.SCHEMA_ID)
                .version(MatchResultEncoder.SCHEMA_VERSION);

        int bodyOffset = offset + headerEncoder.encodedLength();
        matchResultEncoder.wrap(buffer, bodyOffset)
                .matchSeq(matchSeq)
                .symbolId(symbolId)
                .takerUid(takerUid)
                .takerOrderId(takerOrderId)
                .takerShardId(takerShardId);

        List<TradeOrder> safeTrades = (trades != null) ? trades : List.of();
        List<FinishOrder> safeFinishes = (finishOrders != null) ? finishOrders : List.of();

        MatchResultEncoder.TradesEncoder tradesEncoder = matchResultEncoder.tradesCount(safeTrades.size());
        for (TradeOrder trade : safeTrades) {
            tradesEncoder.next()
                    .index(trade.getIndex())
                    .buyUid(nullToZero(trade.getBuyUid()))
                    .sellUid(nullToZero(trade.getSellUid()))
                    .buyOrderId(nullToZero(trade.getBuyOrderId()))
                    .sellOrderId(nullToZero(trade.getSellOrderId()))
                    .buyShardId(trade.getBuyShardId())
                    .sellShardId(trade.getSellShardId())
                    .takerOrderId(nullToZero(trade.getTakerOrderId()))
                    .takerUid(nullToZero(trade.getTakerUid()));
            Decimal64Codec.encode(trade.getPrice(), tradesEncoder.price());
            Decimal64Codec.encode(trade.getVolume(), tradesEncoder.volume());
        }

        MatchResultEncoder.FinishOrdersEncoder finishEncoder =
                matchResultEncoder.finishOrdersCount(safeFinishes.size());
        for (FinishOrder finish : safeFinishes) {
            finishEncoder.next()
                    .uid(nullToZero(finish.getUid()))
                    .orderId(nullToZero(finish.getOrderId()))
                    .status(toSbeFinishStatus(finish.getStatus()))
                    .rejectReason(toSbeRejectReason(finish.getRejectReason()))
                    .shardId(finish.getShardId());
            Decimal64Codec.encode(finish.getLeaveAmount(), finishEncoder.leaveAmount());
            Decimal64Codec.encode(finish.getLeaveVolume(), finishEncoder.leaveVolume());
        }

        return headerEncoder.encodedLength() + matchResultEncoder.encodedLength();
    }

    // ── Snapshot encoding ─────────────────────────────────────────────────────

    public int encodeSnapshotHeader(long nextMatchSeq, int symbolCount,
                                    MutableDirectBuffer buffer, int offset) {
        headerEncoder.wrap(buffer, offset)
                .blockLength(SnapshotHeaderEncoder.BLOCK_LENGTH)
                .templateId(SnapshotHeaderEncoder.TEMPLATE_ID)
                .schemaId(SnapshotHeaderEncoder.SCHEMA_ID)
                .version(SnapshotHeaderEncoder.SCHEMA_VERSION);

        snapshotHeaderEncoder.wrap(buffer, offset + headerEncoder.encodedLength())
                .nextMatchSeq(nextMatchSeq)
                .symbolCount(symbolCount);

        return headerEncoder.encodedLength() + SnapshotHeaderEncoder.BLOCK_LENGTH;
    }

    public int encodeSnapshotSymbolHeader(int symbolId, int orderCount,
                                          long appliedConfigVersion,
                                          int priceScale, int qtyScale,
                                          BigDecimal minQty,
                                          BigDecimal minTradeQuoteAmount,
                                          MutableDirectBuffer buffer, int offset) {
        headerEncoder.wrap(buffer, offset)
                .blockLength(SnapshotSymbolHeaderEncoder.BLOCK_LENGTH)
                .templateId(SnapshotSymbolHeaderEncoder.TEMPLATE_ID)
                .schemaId(SnapshotSymbolHeaderEncoder.SCHEMA_ID)
                .version(SnapshotSymbolHeaderEncoder.SCHEMA_VERSION);

        symbolHeaderEncoder.wrap(buffer, offset + headerEncoder.encodedLength())
                .symbolId(symbolId)
                .orderCount(orderCount)
                .appliedConfigVersion(appliedConfigVersion)
                .priceScale(priceScale)
                .qtyScale(qtyScale);
        Decimal64Codec.encode(minQty, symbolHeaderEncoder.minQty());
        Decimal64Codec.encode(minTradeQuoteAmount, symbolHeaderEncoder.minTradeQuoteAmount());

        return headerEncoder.encodedLength() + SnapshotSymbolHeaderEncoder.BLOCK_LENGTH;
    }

    public int encodeSnapshotBookOrder(int symbolId,
                                       long orderId, long uid, int shardId,
                                       String side,
                                       BigDecimal price,
                                       BigDecimal volume,
                                       BigDecimal remainingVolume,
                                       BigDecimal amount,
                                       BigDecimal remainingAmount,
                                       long seq,
                                       MutableDirectBuffer buffer, int offset) {
        headerEncoder.wrap(buffer, offset)
                .blockLength(SnapshotBookOrderEncoder.BLOCK_LENGTH)
                .templateId(SnapshotBookOrderEncoder.TEMPLATE_ID)
                .schemaId(SnapshotBookOrderEncoder.SCHEMA_ID)
                .version(SnapshotBookOrderEncoder.SCHEMA_VERSION);

        bookOrderEncoder.wrap(buffer, offset + headerEncoder.encodedLength())
                .symbolId(symbolId)
                .orderId(orderId)
                .uid(uid)
                .shardId(shardId)
                .side("BUY".equalsIgnoreCase(side) ? Side.BUY : Side.SELL)
                .seq(seq);
        Decimal64Codec.encode(price, bookOrderEncoder.price());
        Decimal64Codec.encode(volume, bookOrderEncoder.volume());
        Decimal64Codec.encode(remainingVolume, bookOrderEncoder.remainingVolume());
        Decimal64Codec.encode(amount, bookOrderEncoder.amount());
        Decimal64Codec.encode(remainingAmount, bookOrderEncoder.remainingAmount());

        return headerEncoder.encodedLength() + SnapshotBookOrderEncoder.BLOCK_LENGTH;
    }

    // ── Enum mappers (DTO → SBE generated) ───────────────────────────────────

    private static FinishStatus toSbeFinishStatus(com.tk.protocol.dto.FinishStatus status) {
        if (status == null) return FinishStatus.EXCEPTION;
        return switch (status) {
            case COMPLETED -> FinishStatus.COMPLETED;
            case CANCEL -> FinishStatus.CANCEL;
            case PART_CANCEL -> FinishStatus.PART_CANCEL;
            case EXCEPTION -> FinishStatus.EXCEPTION;
            case REJECT -> FinishStatus.REJECT;
            case POST_ONLY_REJECT -> FinishStatus.POST_ONLY_REJECT;
        };
    }

    private static RejectReason toSbeRejectReason(com.tk.protocol.dto.RejectReason reason) {
        if (reason == null) return RejectReason.NONE;
        return switch (reason) {
            case INVALID_ORDER_ID -> RejectReason.INVALID_ORDER_ID;
            case DUPLICATE_ORDER_ID -> RejectReason.DUPLICATE_ORDER_ID;
            case ORDER_EXPIRED -> RejectReason.ORDER_EXPIRED;
            case INVALID_PRICE_TYPE -> RejectReason.INVALID_PRICE_TYPE;
            case INVALID_PRICE -> RejectReason.INVALID_PRICE;
            case PRICE_TICK_INVALID -> RejectReason.PRICE_TICK_INVALID;
            case INVALID_QUANTITY -> RejectReason.INVALID_QUANTITY;
            case INVALID_NOTIONAL -> RejectReason.INVALID_NOTIONAL;
            case INVALID_TIME_IN_FORCE -> RejectReason.INVALID_TIME_IN_FORCE;
            case POST_ONLY_WOULD_CROSS -> RejectReason.POST_ONLY_WOULD_CROSS;
            case FOK_NOT_FILLABLE -> RejectReason.FOK_NOT_FILLABLE;
            case UNKNOWN -> RejectReason.UNKNOWN;
        };
    }

    private static long nullToZero(Long value) {
        return value == null ? 0L : value;
    }
}
