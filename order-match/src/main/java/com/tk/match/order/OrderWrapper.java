package com.tk.match.order;

import com.tx.common.entity.Order;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class OrderWrapper {
    private Order order;
    private Long offset;
    private BigDecimal price; // 无限流动性订单
}
