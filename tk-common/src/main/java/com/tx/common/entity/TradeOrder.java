package com.tx.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 撮合订单
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("trade_order")
public class TradeOrder {

    public enum Role {

        TAKER("taker"),

        MAKER("maker");

        private final String value;

        Role(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    public enum Status {

        PROCESSING(0),

        SUCCESS(1),

        FAIL(2);

        private final int value;

        Status(int value) {
            this.value = value;
        }

        public int value() {
            return value;
        }
    }

    // matchId + orderId 是联合唯一索引
    private Long uid;
    private Long matchId; // 撮合id
    private Long orderId; // 订单id
    private String side; // buy sell
    private BigDecimal price;
    private BigDecimal volume;
    private BigDecimal fee;
    private String role; // taker,maker
    private int status; // 0 处理中 1 处理完成 2 处理失败
    private Long txid;
    private Date ctime;
    private Date mtime;
    private BigDecimal positionBeforeVolume = BigDecimal.ZERO;
    private BigDecimal positionAfterVolume = BigDecimal.ZERO;

    private boolean fullMatch; // 订单是否完全匹配

    public boolean isProcessing() {
        return Status.PROCESSING.value() == status;
    }

}
