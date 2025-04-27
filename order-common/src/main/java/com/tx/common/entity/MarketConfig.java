package com.tx.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/****
 * 市场配置
 * Created by tyler on 17/5/11.
 */
@Data
@TableName("market_config")
public class MarketConfig {
    /**
     * 市场ID
     */
    @TableId(value = "id", type = IdType.AUTO)
  //  @ApiModelProperty(value = "市场ID")
    private Integer id;

    /**
     * 卖方市场ID
     */
    @TableField(value = "sell_coin_id")
  //  @ApiModelProperty(value = "卖方市场ID")
    private Long sellCoinId;

    /**
     * 买方币种ID
     */
    @TableField(value = "buy_coin_id")
  //  @ApiModelProperty(value = "买方币种ID")
    private Long buyCoinId;

    /**
     * 交易对标识
     */
    @TableField(value = "symbol")
  //  @ApiModelProperty(value = "交易对标识")
    private String symbol;

    /**
     * 名称
     */
    @TableField(value = "name")
 //   @ApiModelProperty(value = "名称")
    private String name;

    /**
     * 标题
     */
    @TableField(value = "title")
 //   @ApiModelProperty(value = "标题")
    private String title;

    /**
     * 市场logo
     */
    @TableField(value = "img")
  //  @ApiModelProperty(value = "市场logo")
    private String img;

  //  @ApiModelProperty(value = "手续费率")
    private BigDecimal makerRate;

  //  @ApiModelProperty(value = "手续费率")
    private BigDecimal takerRate;

   // @ApiModelProperty(value = "维持保证金率")
    private BigDecimal liqRate;

    /**
     * 单笔最小委托量
     */
    @TableField(value = "num_min")
  //  @ApiModelProperty(value = "单笔最小委托量")
    private BigDecimal numMin;

    /**
     * 单笔最大委托量
     */
    @TableField(value = "num_max")
 //   @ApiModelProperty(value = "单笔最大委托量")
    private BigDecimal numMax;

    /**
     * 价格小数位
     */
    @TableField(value = "price_scale")
  //  @ApiModelProperty(value = "价格小数位")
    private Byte priceScale;

    /**
     * 数量小数位
     */
    @TableField(value = "num_scale")
   // @ApiModelProperty(value = "数量小数位")
    private Byte numScale;

    /**
     * 排序列
     */
    @TableField(value = "sort")
  //  @ApiModelProperty(value = "排序列")
    private Integer sort = Integer.MAX_VALUE;

    /**
     * 状态
     * 0禁用
     * 1启用
     */
    @TableField(value = "status")
 //   @ApiModelProperty(value = "状态,0禁用,1启用")
    private Integer status = 1;


    /**
     * 更新时间
     */
    @TableField(value = "mtime", fill = FieldFill.INSERT_UPDATE)
  //  @ApiModelProperty(value = "更新时间")
    private Date mtime;

    /**
     * 创建时间
     */
    @TableField(value = "ctime", fill = FieldFill.INSERT)
  //  @ApiModelProperty(value = "创建时间")
    private Date ctime;

    private String matchType; // cfd, 撮合模式

    private String jsonConfig; // cfd 外部接口配置

}
