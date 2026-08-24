package com.llsp.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class CacheStatsService {

    @Resource
    private Cache<Long, Object> shopCache;

    private final AtomicLong redisRequestCount = new AtomicLong(0);
    private final AtomicLong localCacheHitCount = new AtomicLong(0);
    private final AtomicLong localCacheMissCount = new AtomicLong(0);

    public void recordRedisRequest() {
        redisRequestCount.incrementAndGet();
    }

    public void recordLocalCacheHit() {
        localCacheHitCount.incrementAndGet();
    }

    public void recordLocalCacheMiss() {
        localCacheMissCount.incrementAndGet();
    }

    public Map<String, Object> getCacheStats() {
        CacheStats stats = shopCache.stats();
        
        long localHit = localCacheHitCount.get();
        long localMiss = localCacheMissCount.get();
        long redisRequests = redisRequestCount.get();
        long totalRequests = localHit + localMiss;
        
        double localHitRate = totalRequests > 0 ? (double) localHit / totalRequests * 100 : 0;
        
        Map<String, Object> result = new HashMap<>();
        result.put("总请求次数", totalRequests);
        result.put("本地缓存命中次数", localHit);
        result.put("本地缓存未命中次数", localMiss);
        result.put("本地缓存命中率", String.format("%.2f%%", localHitRate));
        result.put("实际Redis请求次数", redisRequests);
        
        long redisRequestsWithoutLocalCache = totalRequests;
        long savedRequests = redisRequestsWithoutLocalCache - redisRequests;
        double reductionRate = totalRequests > 0 ? (double) savedRequests / totalRequests * 100 : 0;
        
        result.put("无本地缓存时Redis请求次数", redisRequestsWithoutLocalCache);
        result.put("节省的Redis请求次数", savedRequests);
        result.put("Redis访问量降低比例", String.format("%.2f%%", reductionRate));
        
        result.put("本地缓存当前大小", shopCache.estimatedSize());
        result.put("Caffeine命中率", String.format("%.2f%%", totalRequests > 0 ? stats.hitRate() * 100 : 0));
        
        return result;
    }

    public void resetStats() {
        redisRequestCount.set(0);
        localCacheHitCount.set(0);
        localCacheMissCount.set(0);
        log.info("缓存统计已重置");
    }
}