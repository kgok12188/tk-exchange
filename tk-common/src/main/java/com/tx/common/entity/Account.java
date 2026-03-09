package com.tx.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName(value = "`account`")
public class Account {
    @TableId(value = "id", type = IdType.AUTO)
    @ApiModelProperty(value = "自增id")
    private Long id;
    private Long uid;
    private String coinName; // 币种名称
    private Long coinId; // 保证金币种
    private BigDecimal availableBalance = BigDecimal.ZERO;
    private BigDecimal crossMarginFrozen = BigDecimal.ZERO; // 全仓下单冻结保证金
    private BigDecimal isolatedMarginFrozen = BigDecimal.ZERO; //逐仓冻结保证金
    private BigDecimal orderFrozen = BigDecimal.ZERO; //  挂单冻结资金
    private Long txid = 0L; // 版本号
    private java.util.Date ctime;
    private java.util.Date mtime;
}
