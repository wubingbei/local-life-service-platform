package com.llsp.service.impl;

import com.llsp.config.BloomFilterConfig;
import com.llsp.dto.Result;
import com.llsp.entity.Shop;
import com.llsp.service.IShopService;
import com.llsp.utils.BloomFilterHelper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import jakarta.annotation.Resource;

import static com.llsp.utils.RedisConstants.BLOOM_SHOP_KEY;
import static com.llsp.utils.RedisConstants.CACHE_SHOP_KEY;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 集成测试：验证新建店铺会同步进布隆过滤器，不会被 queryById 误判拦截
 * 依赖真实 MySQL + Redis
 */
@SpringBootTest
class ShopBloomFilterIntegrationTest {

    @Resource
    private IShopService shopService;
    @Resource
    private BloomFilterHelper bloomFilterHelper;
    @Resource
    private BloomFilterConfig bloomFilterConfig;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void createShop_thenQueryById_shouldNotBeBlockedByBloomFilter() {
        // 1. 创建新店铺（save 到 DB + addElement 到布隆）
        Shop shop = new Shop();
        shop.setName("集成测试店铺-" + System.currentTimeMillis());
        shop.setTypeId(1L);
        shop.setImages("");
        shop.setAddress("测试地址");
        shop.setArea("测试商圈");
        shop.setX(113.362);
        shop.setY(23.159);
        shop.setAvgPrice(50L);
        shop.setSold(0);
        shop.setScore(40);

        shopService.createShop(shop);
        Long id = shop.getId();
        assertNotNull(id, "店铺保存后应回填自增 id");

        try {
            // 2. 修复点：新店铺应立即同步进布隆过滤器
            assertTrue(bloomFilterHelper.mightContain(BLOOM_SHOP_KEY, id),
                    "新建店铺应已同步进布隆过滤器，否则会被 queryById 误拦截");

            // 3. 清掉 Redis 缓存，强制 queryById 走「布隆 + DB」路径
            stringRedisTemplate.delete(CACHE_SHOP_KEY + id);

            // 4. 端到端查询：不应被布隆拦截，应能查到店铺
            Result result = shopService.queryById(id);
            assertTrue(result.getSuccess(),
                    "新店铺不应被布隆拦截，实际错误: " + result.getErrorMsg());
        } finally {
            // 5. 清理：删除测试店铺 + 重建布隆，还原到测试前状态
            shopService.removeById(id);
            bloomFilterConfig.rebuildBloomFilter();
        }
    }
}
