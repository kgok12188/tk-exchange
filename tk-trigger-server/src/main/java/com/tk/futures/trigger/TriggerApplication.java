package com.tk.futures.trigger;


import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan({"com.tx.common", "com.tk.futures.trigger"})
@MapperScan("com.tx.common.mapper")
public class TriggerApplication {
    public static void main(String[] args) {
        org.springframework.boot.SpringApplication.run(TriggerApplication.class, args);
    }

}
