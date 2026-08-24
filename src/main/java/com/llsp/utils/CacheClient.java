package com.llsp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.llsp.utils.RedisConstants.*;


@Component
@Slf4j
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;
    private final ExecutorService cacheRebuildExecutor;
    private com.llsp.service.CacheStatsService cacheStatsService;

    @Value("${llsp.feature.random-ttl-enabled:true}")
    private boolean randomTtlEnabled;

    public CacheClient(StringRedisTemplate stringRedisTemplate, ExecutorService cacheRebuildExecutor) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.cacheRebuildExecutor = cacheRebuildExecutor;
    }

    public void setCacheStatsService(com.llsp.service.CacheStatsService cacheStatsService) {
        this.cacheStatsService = cacheStatsService;
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        long actualSeconds = randomTtlEnabled ? applyRandomTtlSeconds(time) : time;
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(actualSeconds));
        redisData.setData(value);
        // 写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 给 TTL 秒数加 ±20% 随机偏移，防止缓存雪崩
     */
    private long applyRandomTtlSeconds(long seconds) {
        if (!randomTtlEnabled || seconds <= 0) {
            return seconds;
        }
        long offset = ThreadLocalRandom.current().nextLong(-seconds / 5, seconds / 5 + 1);
        return Math.max(1, seconds + offset);
    }

    /**
     * 组合缓存读取：命中走逻辑过期，未命中走互斥锁回填
     * 统一使用 RedisData 格式，与预热数据格式保持一致
     */
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1. 从 Redis 查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2. 命中（key 存在且非空值）
        if (StrUtil.isNotBlank(json)) {
            RedisData redisData = JSONUtil.toBean(json, RedisData.class);
            R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
            LocalDateTime expireTime = redisData.getExpireTime();
            // 2.1 逻辑未过期，直接返回
            if (expireTime != null && expireTime.isAfter(LocalDateTime.now())) {
                return r;
            }
            // 2.2 逻辑已过期，抢锁异步重建，无论是否抢到锁都返回旧数据
            String lockKey = LOCK_SHOP_KEY + id;
            if (trylock(lockKey)) {
                cacheRebuildExecutor.execute(() -> {
                    try {
                        R fresh = dbFallback.apply(id);
                        if (fresh != null) {
                            this.setWithLogicalExpire(key, fresh, time, unit);
                        }
                    } catch (Exception e) {
                        log.error("缓存重建失败, key={}", key, e);
                    } finally {
                        unLock(lockKey);
                    }
                });
            }
            // 2.3 返回旧数据（可能稍旧，但不阻塞）
            return r;
        }

        // 3. 命中空值（穿透防护标记）
        if (json != null) {
            return null;
        }

        // 4. 未命中（key 不存在），互斥锁回填
        String lockKey = LOCK_SHOP_KEY + id;
        R r = null;
        try {
            boolean isLock = trylock(lockKey);
            // 4.1 抢锁失败，短暂休眠后递归重试
            if (!isLock) {
                Thread.sleep(50);
                return queryWithLogicalExpire(keyPrefix, id, type, dbFallback, time, unit);
            }
            // 4.2 抢锁成功，二次检查（双重判定）
            json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)) {
                RedisData redisData = JSONUtil.toBean(json, RedisData.class);
                return JSONUtil.toBean((JSONObject) redisData.getData(), type);
            }
            if (json != null) {
                return null;
            }
            // 4.3 查询数据库
            r = dbFallback.apply(id);
            if (r == null) {
                // 写空值防穿透
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 4.4 回填（同样用逻辑过期格式，与命中路径统一）
            this.setWithLogicalExpire(key, r, time, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } finally {
            // 4.5 释放互斥锁
            unLock(lockKey);
        }
        // 5. 返回
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
