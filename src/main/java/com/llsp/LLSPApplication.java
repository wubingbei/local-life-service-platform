package com.llsp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAspectJAutoProxy(exposeProxy = true)
@EnableScheduling
@MapperScan("com.llsp.mapper")
@SpringBootApplication
@EnableRabbit
public class LLSPApplication {

    public static void main(String[] args) {
        SpringApplication.run(LLSPApplication.class, args);
    }

}
