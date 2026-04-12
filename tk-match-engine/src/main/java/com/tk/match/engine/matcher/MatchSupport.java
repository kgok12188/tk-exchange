package com.tk.match.engine.matcher;

import com.tk.match.engine.BookOrder;
import com.tk.match.engine.PriceCodec;
import com.tk.protocol.dto.FinishOrder;
import com.tk.protocol.dto.RejectReason;
import com.tk.protocol.dto.FinishStatus;
import com.tk.protocol.dto.TradeOrder;

import java.math.BigDecimal;

public class MatchSupport {


    /**
     * 撮合成交价格：用刻度还原为协议 BigDecimal。
     */
    public static TradeOrder buildTrade(long index, long orderReqOffset, long priceTicks, int scale, BigDecimal volume, BookOrder taker, BookOrder maker) {
        BigDecimal decodedPrice = PriceCodec.decode(priceTicks, scale);
        return buildTrade(index, orderReqOffset, decodedPrice, volume, taker, maker);
    }

    public static FinishOrder finishOrder(BookOrder bookOrder, FinishStatus status, BigDecimal leaveVolume) {
        return finishOrder(bookOrder, status, leaveVolume, BigDecimal.ZERO);
    }

    public static FinishOrder finishOrder(BookOrder bookOrder, FinishStatus status, BigDecimal leaveVolume, BigDecimal leaveAmount) {
        return finishOrder(bookOrder, status, leaveVolume, leaveAmount, null);
    }

    public static FinishOrder finishOrder(BookOrder bookOrder, FinishStatus status, BigDecimal leaveVolume, RejectReason rejectReason) {
        return finishOrder(bookOrder, status, leaveVolume, BigDecimal.ZERO, rejectReason);
    }

    public static FinishOrder finishOrder(BookOrder bookOrder, FinishStatus status, BigDecimal leaveVolume, BigDecimal leaveAmount, RejectReason rejectReason) {
        return FinishOrder.builder().uid(bookOrder.getUid()).orderId(bookOrder.getOrderId()).shardId(bookOrder.getShardId())
                .status(status).leaveVolume(leaveVolume)
                .rejectReason(rejectReason)
                .leaveAmount(leaveAmount)//
                .build();
    }

    public static TradeOrder buildTrade(long index, long orderReqOffset, BigDecimal price, BigDecimal volume, BookOrder taker, BookOrder maker) {
        boolean takerBuy = taker.isSideBuy();
        return TradeOrder.builder().index(index).orderReqOffset(orderReqOffset).price(price).volume(volume)
                .buyUid(takerBuy ? taker.getUid() : maker.getUid()) //
                .sellUid(takerBuy ? maker.getUid() : taker.getUid()) //
                .buyOrderId(takerBuy ? taker.getOrderId() : maker.getOrderId()) //
                .sellOrderId(takerBuy ? maker.getOrderId() : taker.getOrderId()) //
                .takerOrderId(taker.getOrderId()).takerUid(taker.getUid()) //
                .buyShardId(takerBuy ? taker.getShardId() : maker.getShardId()) //
                .sellShardId(takerBuy ? maker.getShardId() : taker.getShardId()).build();
    }

}
