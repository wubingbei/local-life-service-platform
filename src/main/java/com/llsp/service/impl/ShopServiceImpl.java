package com.llsp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.llsp.dto.Result;
import com.llsp.entity.Shop;
import com.llsp.mapper.ShopMapper;
import com.llsp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.llsp.utils.CacheClient;
import com.llsp.utils.RedisConstants;
import com.llsp.utils.RedisData;
import com.llsp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.llsp.utils.RedisConstants.*;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;
    @Resource
    private Cache<Long, Object> shopCache;

    @Override
    public Result queryById(Long id) {
        // 一级缓存：Caffeine本地缓存
        Shop shop = getFromLocalCache(id);
        if (shop != null) {
            return Result.ok(shop);
        }
        // 二级缓存：Redis分布式缓存
        shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        // 将数据写入一级缓存
        putToLocalCache(id, shop);
        return Result.ok(shop);
    }

    private Shop getFromLocalCache(Long id) {
        Object obj = shopCache.getIfPresent(id);
        return obj instanceof Shop ? (Shop) obj : null;
    }

    private void putToLocalCache(Long id, Shop shop) {
        shopCache.put(id, shop);
    }

    private void removeFromLocalCache(Long id) {
        shopCache.invalidate(id);
    }

    private static final ExecutorService executor = Executors.newFixedThreadPool(10);

    /**
     * 逻辑过期
     */
    private Shop queryWithLogicalExpire(Long id) {
        // 从redis查询商铺缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 判断是否存在
        if (StrUtil.isBlank(shopJson)){
            // 不存在，返回空
            return null;
        }
        // 把Json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            // 未过期，直接返回
            return shop;
        }
        // 已过期，缓存重建
        // 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = trylock(lockKey);
        // 判断是否获取成功
        if (isLock) {
            // 二次检查，处理另一个线程刚把数据写入redis的情况
            redisData = JSONUtil.toBean(shopJson, RedisData.class);
            shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
            expireTime = redisData.getExpireTime();
            // 判断是否过期
            if (expireTime.isAfter(LocalDateTime.now())){
                // 未过期，直接返回
                return shop;
            }
            // 开启独立线程重建缓存
            executor.submit(()->{
                try {
                    this.saveShop2Redis(id, CACHE_SHOP_TTL);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);
                }
            });
        }
        // 返回过期商铺
        return shop;
    }

    /**
     * 互斥锁
     */
    private Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 判断是否存在
        if (StrUtil.isNotBlank(shopJson)){ // 不是Null，""空值，" "纯空格
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 判断是否为空值
        if (shopJson != null){
            return null;
        }
        // 实现缓存重建
        // 获取互斥锁
        String lock = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = trylock(lock);
            // 判断是否获取锁成功
            if (!isLock) {
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 二次检查，处理另一个线程刚把数据写入redis的情况
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJson)){ // 不是Null，""空值，" "纯空格
                shop = JSONUtil.toBean(shopJson, Shop.class);
                return shop;
            }
            shop = getById(id);
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        } finally {
            unLock(lock);
        }
        return shop;
    }

    /**
     * 缓存穿透
     */
    private Shop queryWithPassTrough(Long id) {
        // 1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)){
            // 3.存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 判断命中的是否是空值
        if (shopJson != null){
            return null;
        }
        // 4.不存在，根据id查询数据库
        Shop shop = getById(id);
        // 5.不存在，返回错误
        if (shop == null) {
            // 将空值写入redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 6.存在，写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 7.返回
        return shop;
    }

    private boolean trylock(String key){
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(success);
    }

    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    /**
     * 将店铺信息保存到redis中，并设置逻辑过期时间
     * @param id 店铺id
     * @param expireSeconds 逻辑过期时间，单位秒
     */
    private void saveShop2Redis(Long id, Long expireSeconds){
        // 查询店铺信息
        Shop shop = getById(id);
        // 封装进RedisData
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 保存到redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
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
        // 2.删除一级缓存（本地）
        removeFromLocalCache(id);
        // 3.删除二级缓存（Redis）
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
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
            return Result.ok(Collections.emptyList());
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
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
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
}
