package com.llsp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.llsp.dto.Result;
import com.llsp.entity.Shop;
import com.llsp.entity.ShopComments;
import com.llsp.mapper.ShopCommentsMapper;
import com.llsp.mapper.ShopMapper;
import com.llsp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.llsp.utils.BloomFilterHelper;
import com.llsp.utils.CacheClient;
import com.llsp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.llsp.utils.RedisConstants.*;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;
    @Resource
    private Cache<Long, Object> shopCache;
    @Resource
    private ShopCommentsMapper shopCommentsMapper;
    @Resource
    private com.llsp.service.CacheStatsService cacheStatsService;
    @Resource
    private BloomFilterHelper bloomFilterHelper;

    @Value("${llsp.feature.bloom-filter-enabled:true}")
    private boolean bloomFilterEnabled;
    @Value("${llsp.feature.hot-preload-enabled:true}")
    private boolean hotPreloadEnabled;

    @Override
    public Result queryById(Long id) {
        // 0. 布隆过滤器预判（防缓存穿透）
        if (bloomFilterEnabled && !bloomFilterHelper.mightContain(BLOOM_SHOP_KEY, id)) {
            cacheStatsService.recordLocalCacheMiss();
            return Result.fail("店铺不存在");
        }
        // 一级缓存：Caffeine本地缓存
        Shop shop = getFromLocalCache(id);
        if (shop != null) {
            // 填充真实评论数
            populateRealCommentCounts(Collections.singletonList(shop));
            return Result.ok(shop);
        }
        // 二级缓存：Redis分布式缓存
        shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        // 填充真实评论数
        populateRealCommentCounts(Collections.singletonList(shop));
        // 将数据写入一级缓存
        putToLocalCache(id, shop);
        return Result.ok(shop);
    }

    /**
     * 新增店铺：落库后同步写入布隆过滤器，避免新店铺被误判拦截
     */
    @Override
    @Transactional
    public Result createShop(Shop shop) {
        save(shop);
        if (bloomFilterEnabled) {
            bloomFilterHelper.addElement(BLOOM_SHOP_KEY, shop.getId());
        }
        return Result.ok(shop.getId());
    }

    /**
     * 从本地缓存中获取数据
     */
    private Shop getFromLocalCache(Long id) {
        Object obj = shopCache.getIfPresent(id);
        if (obj instanceof Shop) {
            cacheStatsService.recordLocalCacheHit();
            return (Shop) obj;
        }
        cacheStatsService.recordLocalCacheMiss();
        return null;
    }

    /**
     * 写入本地缓存
     */
    private void putToLocalCache(Long id, Shop shop) {
        shopCache.put(id, shop);
    }

    /***
     * 删除本地缓存
     */
    private void removeFromLocalCache(Long id) {
        shopCache.invalidate(id);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1.更新数据库
        updateById(shop);
        // 2.删除二级缓存（Redis）
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        // 3.删除一级缓存（本地）
        removeFromLocalCache(id);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 填充真实评论数
            populateRealCommentCounts(page.getRecords());
            return Result.ok(page.getRecords());
        }
        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        // 3.查询redis，按照距离排序，分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(
                key,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
        );
        // 4.解析出id
        if (results == null) {
            // Redis GEO 无数据，回退到数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            populateRealCommentCounts(page.getRecords());
            return Result.ok(page.getRecords());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1 截取from~end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2 获取店铺 id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3 获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据 id 查询 shop
        List<Shop> shops = query().in("id", ids).list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 填充真实评论数
        populateRealCommentCounts(shops);
        // 6.返回
        return Result.ok(shops);
    }

    /**
     * 根据名称关键字搜索商铺
     */
    @Override
    public Result queryShopByName(String name, Integer current, Integer size) {
        int pageSize = size != null ? size : SystemConstants.MAX_PAGE_SIZE;
        Page<Shop> page = query()
                .like(StrUtil.isNotBlank(name), "name", name)
                .page(new Page<>(current, pageSize));
        // 填充真实评论数
        populateRealCommentCounts(page.getRecords());
        return Result.ok(page.getRecords());
    }

    /**
     * 填充真实评论数
     */
    private void populateRealCommentCounts(List<Shop> shops) {
        if (shops == null || shops.isEmpty()) {
            return;
        }
        // 收集所有店铺ID
        List<Long> shopIds = shops.stream()
                .map(Shop::getId)
                .collect(Collectors.toList());
        // 批量查询每个店铺的真实评论数
        List<Map<String, Object>> countResults = shopCommentsMapper.selectMaps(
                new QueryWrapper<ShopComments>()
                        .select("shop_id", "count(1) as cnt")
                        .in("shop_id", shopIds)
                        .groupBy("shop_id")
        );
        // 构建 shopId -> 真实评论数 的映射
        Map<Long, Integer> countMap = new HashMap<>();
        for (Map<String, Object> row : countResults) {
            Long shopId = ((Number) row.get("shop_id")).longValue();
            Integer cnt = ((Number) row.get("cnt")).intValue();
            countMap.put(shopId, cnt);
        }
        // 覆盖静态 comments 字段为真实评论数
        for (Shop shop : shops) {
            shop.setComments(countMap.getOrDefault(shop.getId(), 0));
        }
    }

    @Override
    public Result queryShopsWithSort(Integer typeId, String sortBy, Integer current) {
        // 1.构建查询条件
        LambdaQueryWrapper<Shop> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Shop::getTypeId, typeId);
        
        // 2.根据sortBy参数设置排序规则
        if ("score".equals(sortBy)) {
            // 按评分降序（评分存储时乘以了10，需要除以10）
            wrapper.orderByDesc(Shop::getScore);
        } else if ("sold".equals(sortBy)) {
            // 按销量降序
            wrapper.orderByDesc(Shop::getSold);
        } else if ("price".equals(sortBy)) {
            // 按均价升序（从便宜到贵）
            wrapper.orderByAsc(Shop::getAvgPrice);
        } else {
            // 默认按销量降序
            wrapper.orderByDesc(Shop::getSold);
        }
        
        // 3.分页查询
        Page<Shop> page = new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE);
        Page<Shop> result = page(page, wrapper);
        
        return Result.ok(result.getRecords());
    }

    /**
     * 启动时预热热门店铺到本地缓存和 Redis
     * 确保系统冷启动时缓存不为空，避免首波请求全部穿透到 DB
     */
    @PostConstruct
    public void preloadHotShops() {
        if (!hotPreloadEnabled) {
            log.debug("热点预热功能已关闭 (llsp.feature.hot-preload-enabled=false)");
            return;
        }
        try {
            List<Shop> hotShops = query()
                    .orderByDesc("sold")
                    .last("LIMIT 50")
                    .list();
            if (hotShops == null || hotShops.isEmpty()) {
                log.debug("没有需要预热的热门店铺");
                return;
            }
            for (Shop shop : hotShops) {
                // 写入 Caffeine 本地缓存
                shopCache.put(shop.getId(), shop);
                // 写入 Redis（逻辑过期模式，分散 TTL 防雪崩）
                cacheClient.setWithLogicalExpire(
                        CACHE_SHOP_KEY + shop.getId(), shop, CACHE_SHOP_TTL, TimeUnit.MINUTES);
            }
            log.debug("预热完成，已加载 " + hotShops.size() + " 个热门店铺到缓存");
        } catch (Exception e) {
            log.error("热点预热失败（不影响正常服务）", e);
        }
    }
}