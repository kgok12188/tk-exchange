package com.tx.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.google.common.base.Objects;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.util.Date;

@Data
@NoArgsConstructor
@TableName("co_position")
public class Position {


    public enum Status {
        /**
         * 1 未完成 0 已完成
         */
        OPEN(1),
        CLOSE(0);

        private int value;

        Status(int value) {
            this.value = value;
        }

        public int value() {
            return value;
        }
    }


    public enum PositionType {
        /**
         * 0 全仓 1 逐仓
         */
        CROSS(0),
        ISOLATED(1);

        private int value;

        PositionType(int value) {
            this.value = value;
        }

        public int value() {
            return value;
        }

        public static PositionType fromValue(Integer value) {
            if (value == null) {
                return null;
            }
            for (PositionType type : PositionType.values()) {
                if (type.value == value) {
                    return type;
                }
            }
            return null;
        }
    }


    public enum LiqStatus {
        /**
         * 0 正常平仓 1 爆仓
         */
        NORMAL(0),
        LIQ(1);

        private int value;

        LiqStatus(int value) {
            this.value = value;
        }

        public int value() {
            return value;
        }
    }

    /**
     * 本次订单的Id
     */
    private Long id;

    /**
     * 用户/会员Id
     */
    private Long uid;

    private String symbol; // 交易对 BTC-USDT / ETH-USDT

    private Integer marketId; // 交易对 BTC-USDT / ETH-USDT

    private BigDecimal volume = BigDecimal.ZERO; // 可以平仓数量

    private BigDecimal closeVolume = BigDecimal.ZERO; // 已经平仓的数据

    private BigDecimal pendingCloseVolume = BigDecimal.ZERO; // 待平仓的数据

    private Integer leverageLevel = 0; // 杠杆倍数
    private BigDecimal fee = BigDecimal.ZERO;

    private BigDecimal openPrice = BigDecimal.ZERO; // 持仓均价

    private BigDecimal closePrice = BigDecimal.ZERO; // 平仓均价

    private BigDecimal holdAmount = BigDecimal.ZERO; // 锁定的保证金

    private BigDecimal realizedAmount = BigDecimal.ZERO;

    private Integer status = 1; // 1 未完成 0 已完成

    private Integer positionType = 0; // 0 全仓 1 逐仓

    private Long liqOrderId;  // 0 正常平仓 1 爆仓

    private String side;

    /**
     * 挂单时间
     */
    private Date ctime;

    private Date mtime;

    private Long txid;


    public boolean isBuy() {
        return StringUtils.equals(Order.OrderSide.BUY.value, side);
    }

    public boolean isCross() { // 是否全仓
        return Objects.equal(positionType, PositionType.CROSS.value);
    }

}
