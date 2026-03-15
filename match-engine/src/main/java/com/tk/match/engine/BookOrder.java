package com.tk.match.engine;

import lombok.Data;

import java.math.BigDecimal;

/**
 * In-book order representation: immutable identity/price, mutable remaining volume.
 * Used only inside OrderBook; not part of protocol.
 */
@Data
public class BookOrder {

    private long orderId;
    private Long uid;
    private int shardId;
    private String side;
    private BigDecimal price;
    private BigDecimal remainingVolume;
    private BigDecimal volume;
    private long seq;
    private boolean sideBuy;

    public BookOrder() {

    }

    public BookOrder(Long id, Long uid, int shardId, String side, BigDecimal price, BigDecimal remainingVolume, long seq) {
        this.orderId = id != null ? id : 0L;
        this.uid = uid;
        this.shardId = shardId;
        this.side = side;
        this.price = price;
        this.remainingVolume = remainingVolume;
        this.volume = remainingVolume;
        this.seq = seq;
        this.sideBuy = "BUY".equalsIgnoreCase(side);
    }

    public BigDecimal getRemainingVolume() {
        return remainingVolume == null ? BigDecimal.ZERO : remainingVolume;
    }

}
