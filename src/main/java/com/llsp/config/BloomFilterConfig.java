package com.llsp.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.llsp.entity.Shop;
import com.llsp.mapper.ShopMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.util.List;

/**
 * 布隆过滤器配置
 * 启动时从 DB 全量加载店铺 ID 到布隆过滤器
 */
@Slf4j
@Configuration
public class BloomFilterConfig {

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private ShopMapper shopMapper;

    @Value("${llsp.feature.bloom-filter-enabled:true}")
    private boolean bloomFilterEnabled;

    /**
     * 布隆过滤器名称常量
     */
    public static final String SHOP_BLOOM_FILTER = "bloom:shop";

    /**
     * 预期插入量（可根据业务增长调整）
     */
    private static final long EXPECTED_INSERTIONS = 10000L;

    /**
     * 误判率 3%
     */
    private static final double FALSE_PROBABILITY = 0.03;

    @PostConstruct
    public void initBloomFilter() {
        if (!bloomFilterEnabled) {
            log.info("布隆过滤器功能已关闭 (llsp.feature.bloom-filter-enabled=false)");
            return;
        }

        try {
            RBloomFilter<String> filter = redissonClient.getBloomFilter(SHOP_BLOOM_FILTER);
            // 如果已存在则先删除再重建，确保数据干净
            if (filter.isExists()) {
                filter.delete();
                log.info("已删除旧的布隆过滤器 [{}]", SHOP_BLOOM_FILTER);
            }

            // 初始化布隆过滤器
            filter.tryInit(EXPECTED_INSERTIONS, FALSE_PROBABILITY);
            log.info("布隆过滤器 [{}] 初始化完成，预期容量={}，误判率={}",
                    SHOP_BLOOM_FILTER, EXPECTED_INSERTIONS, FALSE_PROBABILITY);

            // 从数据库全量加载已存在的店铺 ID
            loadAllShopIds(filter);

        } catch (Exception e) {
            log.error("布隆过滤器初始化失败，功能将不可用（不影响主流程）", e);
        }
    }

    /**
     * 从数据库加载全部店铺 ID 到布隆过滤器
     */
    private void loadAllShopIds(RBloomFilter<String> filter) {
        try {
            List<Shop> shops = shopMapper.selectList(
                    new LambdaQueryWrapper<Shop>().select(Shop::getId));
            for (Shop shop : shops) {
                filter.add(shop.getId().toString());
            }
            log.info("布隆过滤器 [{}] 已加载 {} 个店铺ID", SHOP_BLOOM_FILTER, shops.size());
        } catch (Exception e) {
            log.error("加载店铺ID到布隆过滤器失败", e);
        }
    }

    /**
     * 重建布隆过滤器（供定时任务调用，消除误判累积）
     */
    public void rebuildBloomFilter() {
        if (!bloomFilterEnabled) {
            return;
        }
        try {
            RBloomFilter<String> filter = redissonClient.getBloomFilter(SHOP_BLOOM_FILTER);
            filter.delete();
            filter.tryInit(EXPECTED_INSERTIONS, FALSE_PROBABILITY);
            loadAllShopIds(filter);
            log.info("布隆过滤器 [{}] 重建完成，当前元素数={}", SHOP_BLOOM_FILTER, filter.count());
        } catch (Exception e) {
            log.error("布隆过滤器重建失败", e);
        }
    }
}
