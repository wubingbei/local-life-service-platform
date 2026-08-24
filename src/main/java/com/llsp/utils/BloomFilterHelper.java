package com.llsp.utils;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 布隆过滤器工具类
 * 用于防缓存穿透，在查询 Redis/DB 之前快速判断 key 是否可能存在
 */
@Slf4j
@Component
public class BloomFilterHelper {

    @Resource
    private RedissonClient redissonClient;

    /**
     * 判断元素是否可能存在
     * @param filterName 布隆过滤器名称
     * @param element 待判断的元素
     * @return true=可能存在, false=一定不存在
     */
    public boolean mightContain(String filterName, String element) {
        RBloomFilter<String> filter = redissonClient.getBloomFilter(filterName);
        if (!filter.isExists()) {
            log.warn("布隆过滤器 [{}] 尚未初始化，放行请求", filterName);
            return true; // 未初始化时放行，保证可用性
        }
        return filter.contains(element);
    }

    /**
     * 判断元素是否可能存在（Long 类型重载）
     */
    public boolean mightContain(String filterName, Long element) {
        return mightContain(filterName, element.toString());
    }

    /**
     * 向布隆过滤器中添加元素
     * @param filterName 布隆过滤器名称
     * @param element 待添加的元素
     */
    public void addElement(String filterName, String element) {
        RBloomFilter<String> filter = redissonClient.getBloomFilter(filterName);
        if (filter.isExists()) {
            filter.add(element);
        } else {
            log.warn("布隆过滤器 [{}] 尚未初始化，无法添加元素", filterName);
        }
    }

    /**
     * 向布隆过滤器中添加元素（Long 类型重载）
     */
    public void addElement(String filterName, Long element) {
        addElement(filterName, element.toString());
    }

    /**
     * 获取布隆过滤器已添加的元素数量
     */
    public long getCount(String filterName) {
        RBloomFilter<String> filter = redissonClient.getBloomFilter(filterName);
        return filter.isExists() ? filter.count() : 0;
    }

    /**
     * 检查布隆过滤器是否已初始化
     */
    public boolean isInitialized(String filterName) {
        RBloomFilter<String> filter = redissonClient.getBloomFilter(filterName);
        return filter.isExists();
    }
}
