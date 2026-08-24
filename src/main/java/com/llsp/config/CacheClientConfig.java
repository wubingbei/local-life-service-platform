package com.llsp.config;

import com.llsp.service.CacheStatsService;
import com.llsp.utils.CacheClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CacheClientConfig {

    @Resource
    private CacheClient cacheClient;
    
    @Resource
    private CacheStatsService cacheStatsService;

    @PostConstruct
    public void init() {
        cacheClient.setCacheStatsService(cacheStatsService);
    }
}