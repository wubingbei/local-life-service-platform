package com.llsp.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class CaffeineConfig {

    @Bean
    public Cache<Long, Object> shopCache() {
        return Caffeine.newBuilder()
                .initialCapacity(100)   //设置缓存的初始容量为 100 个条目，预先分配内存以提高性能
                .maximumSize(500)       //设置缓存最大容量为 500 个条目
                .expireAfterAccess(3, TimeUnit.MINUTES) //设置缓存过期策略：缓存条目在3分钟内没有被访问
                .recordStats() //开启缓存统计功能，记录缓存命中率、未命中率、未命中次数等信息
                .build();
    }
}