package com.llsp;

import com.llsp.service.CacheStatsService;
import com.llsp.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import jakarta.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
@Disabled("打包时跳过测试")
public class CacheStatsTest {

    @Resource
    private ShopServiceImpl shopService;
    
    @Resource
    private CacheStatsService cacheStatsService;

    @Test
    void testCacheStats() throws InterruptedException {
        System.out.println("========== 缓存统计测试 ==========");
        System.out.println("测试说明：模拟1000次并发请求，统计Redis访问量降低情况\n");
        
        int threadCount = 100;
        int requestsPerThread = 10;
        int totalRequests = threadCount * requestsPerThread;
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        
        System.out.println("【测试配置】");
        System.out.println("- 并发线程数: " + threadCount);
        System.out.println("- 每线程请求数: " + requestsPerThread);
        System.out.println("- 总请求数: " + totalRequests);
        System.out.println("- 测试店铺ID: 1-5（重复访问相同数据）\n");
        
        System.out.println("开始测试...");
        long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < requestsPerThread; j++) {
                        Long shopId = 1L + (j % 5);
                        shopService.queryById(shopId);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await();
        long endTime = System.currentTimeMillis();
        
        System.out.println("测试完成，耗时: " + (endTime - startTime) + " ms\n");
        System.out.println("========== 统计结果 ==========");
        
        var stats = cacheStatsService.getCacheStats();
        System.out.println("总请求次数: " + stats.get("总请求次数"));
        System.out.println("本地缓存命中次数: " + stats.get("本地缓存命中次数"));
        System.out.println("本地缓存未命中次数: " + stats.get("本地缓存未命中次数"));
        System.out.println("本地缓存命中率: " + stats.get("本地缓存命中率"));
        System.out.println("");
        System.out.println("【Redis访问量对比】");
        System.out.println("无本地缓存时Redis请求次数: " + stats.get("无本地缓存时Redis请求次数"));
        System.out.println("有本地缓存时Redis请求次数: " + stats.get("实际Redis请求次数"));
        System.out.println("节省的Redis请求次数: " + stats.get("节省的Redis请求次数"));
        System.out.println("Redis访问量降低比例: " + stats.get("Redis访问量降低比例"));
        
        executor.shutdown();
    }

    @Test
    void resetAndTest() throws InterruptedException {
        cacheStatsService.resetStats();
        Thread.sleep(100);
        testCacheStats();
    }
}