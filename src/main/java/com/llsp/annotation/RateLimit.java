package com.llsp.annotation;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

/**
 * 令牌桶限流注解
 * 基于 Redisson RRateLimiter 实现，默认每秒 100 个请求
 * 支持 Controller 方法级别声明式限流
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /**
     * 每秒允许的请求数
     */
    double rate() default 100.0;

    /**
     * 限流时间窗口，默认 1 秒
     */
    long interval() default 1;

    /**
     * 时间单位
     */
    TimeUnit timeUnit() default TimeUnit.SECONDS;

    /**
     * 限流 key 前缀，默认为方法全限定名
     * 如果指定则对所有方法共享同一个限流器
     */
    String key() default "";

    /**
     * 每用户每秒允许的请求数，0 表示不启用每用户限流
     */
    double perUserRate() default 0;

    /**
     * 限流触发时返回的错误信息
     */
    String message() default "操作过于频繁，请稍后再试";
}
