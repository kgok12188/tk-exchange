package com.tk.futures;

import com.alibaba.fastjson.serializer.SerializeConfig;
import com.tx.common.config.BigDecimalToStringSerializer;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

import java.math.BigDecimal;

@SpringBootApplication
@ComponentScan({"com.tx.common", "com.tk.futures"})
@MapperScan("com.tx.common.mapper")
public class OrderServiceApplication {

    public static void main(String[] args) {
        SerializeConfig.getGlobalInstance().put(BigDecimal.class, new BigDecimalToStringSerializer());
        SpringApplication.run(OrderServiceApplication.class, args);
    }

}
