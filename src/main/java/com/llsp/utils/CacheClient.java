package com.llsp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.llsp.utils.RedisConstants.*;


@Component
@Slf4j
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    // <R, ID>声明泛型，告诉编译器有哪些占位符，R是返回类型
    public <R, ID> R queryWithMutex(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {

        String key = keyPrefix + id;
        log.info("缓存key为：{}", key);
        // 1.从redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(json)){
            // 3.存在，直接返回
            log.info("缓存命中！");
            return JSONUtil.toBean(json, type);
        }
        // 判断命中的是否是空值
        if (json != null){
            return null;
        }
        // 4.不存在，尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        R r = null;
        try {
            boolean isLock = trylock(lockKey);
            // 5.判断是否获取成功
            if (!isLock) {
                // 5.1 获取失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
            }
            // 5.2 获取成功，二次检查缓存
            json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)) {
                return JSONUtil.toBean(json, type);
            }
            if (json != null) {
                return null;
            }
            // 6.根据id查询数据库
            log.info("缓存未命中，查询数据库");
            r = dbFallback.apply(id);
            // 7.判断数据库是否存在
            if (r == null) {
                // 将空值写入redis
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 8.写入redis
            this.set(key, r, time, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 9.释放互斥锁
            unLock(lockKey);
        }
        // 10.返回
        return r;
    }

    private static final ExecutorService executor = Executors.newFixedThreadPool(10);

    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isBlank(json)){
            // 3.不存在，返回为空
            return null;
        }
        // 4.命中，需要先把json反序列化成对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            // 5.1未过期，直接返回店铺信息
            return r;
        }
        // 5.2已过期，需要缓存重建
        // 5.2.1获得互斥锁
        String lockKey = "lock" + key;
        boolean isLock = trylock(lockKey);
        // 5.2.2判断是否获取成功
        if (isLock) {
            // 二次检查，处理另一个线程刚把数据写入redis的情况
            redisData = JSONUtil.toBean(json, RedisData.class);
            r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
            expireTime = redisData.getExpireTime();
            if (expireTime.isAfter(LocalDateTime.now())){
                return r;
            }
            // 5.2.3成功，开启独立线程，实现缓存重建
            executor.submit(() -> {
                try {
                    // 查询数据库
                    R r1 = dbFallback.apply(id);
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);
                }
            });
        }
        // 5.2.4返回过期的店铺信息
        return r;
    }

    private boolean trylock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10L, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }
}
