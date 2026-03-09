package com.tk.futures.api;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan({"com.tx.common","com.tk.futures.api"})
@MapperScan("com.tx.common.mapper")
public class OrderPublicApiApplication {
    public static void main(String[] args) {
       org.springframework.boot.SpringApplication.run(OrderPublicApiApplication.class, args);
    }

}
