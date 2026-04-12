package com.tk.futures.job;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan({"com.tx.common", "com.tk.futures.job"})
@MapperScan("com.tx.common.mapper")
public class JobApplication {

    public static void main(String[] args) {
        org.springframework.boot.SpringApplication.run(JobApplication.class, args);
    }

}
