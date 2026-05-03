package com.llsp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@EnableAspectJAutoProxy(exposeProxy = true)
@MapperScan("com.llsp.mapper")
@SpringBootApplication
public class LLSPApplication {

    public static void main(String[] args) {
        SpringApplication.run(LLSPApplication.class, args);
    }

}
