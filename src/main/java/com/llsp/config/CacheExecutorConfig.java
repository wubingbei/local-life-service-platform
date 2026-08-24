package com.llsp.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 缓存重建线程池配置
 * 逻辑过期模式下用于异步重建缓存，替代原先各自 newFixedThreadPool 的静态线程池
 */
@Slf4j
@Configuration
public class CacheExecutorConfig {

    @Bean(name = "cacheRebuildExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor cacheRebuildExecutor() {
        return new ThreadPoolExecutor(
                4,                                  // 核心线程数
                10,                                 // 最大线程数
                60L, TimeUnit.SECONDS,              // 空闲线程存活时间
                new LinkedBlockingQueue<>(200),     // 有界队列，防止重建任务堆积导致 OOM
                new ThreadFactory() {
                    private final AtomicInteger seq = new AtomicInteger(1);
                    @Override
                    public Thread newThread(Runnable r) {
                        return new Thread(r, "cache-rebuild-" + seq.getAndIncrement());
                    }
                },
                new RejectedExecutionHandler() {    // 队列满时丢弃重建任务并打日志，不抛异常
                    @Override
                    public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
                        log.warn("缓存重建任务被丢弃（队列已满），旧数据仍可用，下次请求会重新触发重建");
                    }
                }
        );
    }
}
