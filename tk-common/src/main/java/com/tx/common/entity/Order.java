package com.tx.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import java.util.Objects;


@Data
@NoArgsConstructor
@TableName("co_order")
public class Order implements Serializable {

    public enum PriceType {
        /**
         * 限价单
         */
        LIMIT(1),
        /**
         * 市价单
         */
        MARKET(0);

        private final int value;

        PriceType(int value) {
            this.value = value;
        }

        public int value() {
            return value;
        }

        public static PriceType fromValue(Integer value) {
            if (value == null) {
                return null;
            }
            for (PriceType priceType : values()) {
                if (priceType.value == value) {
                    return priceType;
                }
            }
            return null;
        }

    }

    public enum OrderStatus {
        /**
         * 初始化
         */
        INIT(0),
        /**
         * 部分成交
         */
        PART_DEAL(1),
        /**
         * 完全成交
         */
        COMPLETED(2),
        /**
         * 部分成交撤销
         */
        PART_CANCEL(3),
        /**
         * 撤销
         */
        CANCEL(4),
        /**
         * 异常
         */
        EXCEPTION(5);

        private int value;

        OrderStatus(int value) {
            this.value = value;
        }

        public int value() {
            return value;
        }
    }

    public enum DealType {
        /**
         * 按照金额买入的数量 比如 10000USDT
         */
        AMOUNT(0),
        /**
         * 按照数量买入的数量 比如 1BTC
         */
        VOLUME(1);

        private int value;

        DealType(int value) {
            this.value = value;
        }

        public int value() {
            return value;
        }

        public static DealType fromValue(Integer value) {
            for (DealType dealType : values()) {
                if (dealType.value == value) {
                    return dealType;
                }
            }
            return null;
        }
    }

    public enum OrderSide {
        BUY("BUY", "trade.buy", "买入"),
        SELL("SELL", "trade.sell", "卖出");

        public String value;
        public String languageKey;
        public String description;

        OrderSide(String value, String languageKey, String description) {
            this.value = value;
            this.languageKey = languageKey;
            this.description = description;
        }


        public static OrderSide fromValue(String value) {
            if (StringUtils.isBlank(value))
                return null;
            for (OrderSide t : OrderSide.values()) {
                if (t.value.equals(value)) {
                    return t;
                }
            }
            return null;
        }
    }

    public enum OPEN {
        /**
         * 开仓
         */
        OPEN,
        /**
         * 平仓
         */
        CLOSE;

        public static OPEN fromValue(String value) {
            for (OPEN t : OPEN.values()) {
                if (t.name().equals(value)) {
                    return t;
                }
            }
            return null;
        }
    }

    /**
     * 本次订单的Id
     */
    private Long id; // 雪花算法生成的Id

    /**
     * 用户/会员Id
     */
    private Long uid;

    private Long positionId = 0L;

    private String symbol = "BTC-USDT"; // 交易对 BTC-USDT / ETH-USDT

    private Integer marketId = 1; // 交易对 BTC-USDT / ETH-USDT

    /**
     * 按照金额买入的数量 比如 10000USDT
     */
    private BigDecimal amount = BigDecimal.ZERO;

    /**
     * 按照数量买入的数量 比如 1BTC
     */
    private BigDecimal volume = BigDecimal.ZERO;

    private Integer dealType; // 0 按照amount下单 1 按照 volume 下单,amount 是锁定金额

    /**
     * 成交金额
     */
    private BigDecimal dealAmount = BigDecimal.ZERO;
    /**
     * 成交数量
     */
    private BigDecimal dealVolume = BigDecimal.ZERO;

    private Integer priceType; // 参考 OrderType 说明

    /**
     * 挂单的价格
     */
    private BigDecimal price = BigDecimal.ZERO;

    /**
     * 平均成交价
     */
    private BigDecimal avgDealPrice = BigDecimal.ZERO;

    private BigDecimal fee = BigDecimal.ZERO;
    /**
     * 订单状态
     */
    private Integer status = 0 ; // 0 初始化 1 部分成交 2 完全成交 3 部分成交撤销 4 撤销 5 异常

    private Integer leverageLevel = 0;
    /**
     * 订单的方向
     */
    private String side; // OrderSide.BUY SELL

    private String open; // OPEN 开仓 CLOSE 平仓

    private Integer positionType; // 0 全仓 1 逐仓 com.tx.common.entity.Position.PositionType

    private BigDecimal realizedAmount = BigDecimal.ZERO;

    /**
     * 挂单时间
     */
    private Date ctime;

    private Date mtime;

    /**
     * 交易完成时间
     */
    private Date completedTime;

    /**
     * 交易取消时间
     */
    private Date cancelTime;

    private Integer cancelOrder = 0;

    private BigDecimal margin = BigDecimal.ZERO; // 订单保证金

    private Long txid = 0L;

    public boolean isBuy() {
        return Objects.equals(OrderSide.BUY.value, side);
    }

    public boolean isSell() {
        return Objects.equals(OrderSide.SELL.value, side);
    }

    public boolean cancel() {
        return Objects.equals(1, cancelOrder);
    }

    public boolean isLimit() { // 是否限价
        return Objects.equals(1, priceType);
    }

    public boolean isMarket() { // 是否市价
        return Objects.equals(0, priceType);
    }

    public boolean isOpenPosition() { // 开仓单子
        return Objects.equals(OPEN.OPEN.name(), open);
    }

    public boolean isClosePosition() { // 平仓单子
        return Objects.equals(OPEN.CLOSE.name(), open);
    }

    public boolean isDealTypeAmount() {
        return Objects.equals(DealType.AMOUNT.value, dealType);
    }

    public boolean isDealTypeVolume() {
        return Objects.equals(DealType.VOLUME.value, dealType);
    }

}