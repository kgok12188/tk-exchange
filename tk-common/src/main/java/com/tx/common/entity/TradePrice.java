package com.tx.common.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TradePrice {

    private Integer marketId;
    private BigDecimal price;
    private Long time;
    private BigDecimal marginRate = new BigDecimal("0.001");

}
