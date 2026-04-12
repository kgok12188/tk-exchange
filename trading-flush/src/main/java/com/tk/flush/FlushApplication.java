package com.tk.flush;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan({"com.tx.common", "com.tk.flush"})
@MapperScan("com.tx.common.mapper")
public class FlushApplication {
    public static void main(String[] args) {
        org.springframework.boot.SpringApplication.run(FlushApplication.class, args);
    }
}
