package com.tx.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import java.util.Objects;

/**
 * <p>
 *
 * </p>
 *
 * @author gfc
 * @since 2020-09-15
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TriggerOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 客户端订单标识
     */
    private String clientId;

    /**
     * 与订单同时下单的id
     */
    private Long orderBeforeId;


    /**
     * 条件单触发后的下单ID
     */
    private Long orderAfterId;


    /**
     * 止盈或者止损单对应的仓位ID
     */
    private Integer positionId;

    /**
     * 用户id
     */
    private Integer uid;

    /**
     * 条件单类型（1 stop loss，2 take profit，3 stop loss limit，4 take profit limit） 参考枚举  com.chainup.futures.order.enums.TriggerType
     */
    private Integer triggerType;

    /**
     * 有效方式（1 GTC, 2 IOC, 3 FOK, 4GTX, 5 PostOnly）
     */
    @TableField("time_in_force")
    private Integer timeInForce;

    /**
     * 触发价格
     */
    private BigDecimal triggerPrice;


    /**
     * 计划委托触发价格类型: 1 标记 2 最新
     */
    private Integer triggerPriceType;

    /**
     * 持仓类型(1 全仓，2 仓逐)
     */
    private Integer positionType;

    /**
     * 开仓类型(1 合仓，2 分仓)
     */
    private Integer openPositionType;

    /**
     * 开平仓方向(open 开仓，close 平仓)
     */
    private String open;

    /**
     * 买卖方向（buy 买入，sell 卖出）
     */
    private String side;

    /**
     * 杠杆倍数
     */
    private Integer leverageLevel;

    /**
     * 下单价格
     */
    private BigDecimal price;

    /**
     * 下单数量(开仓市价单：金额)
     */
    private BigDecimal volume;

    /**
     * 订单来源（订单来源：1web，2app，3api，4其它）
     */
    private Integer source;

    /**
     * 有效状态（0有效，1已过期，2已完成，3触发失败）
     */
    private Integer status;

    /**
     * 订单状态备注
     */
    private Integer memo;

    /**
     * 条件单到期时间
     */
    private Date expireTime;

    /**
     * 创建时间
     */
    private Date ctime;

    /**
     * 更新时间
     */
    private Date mtime;


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TriggerOrder that = (TriggerOrder) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @JsonIgnore
    public Boolean isBuy() {
        return Order.OrderSide.BUY.value.equals(this.getSide());
    }

}
