package com.llsp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 30L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";

    public static final String SMS_LIMIT_KEY = "sms:limit:";
    public static final Long SMS_LIMIT_TTL = 60L;
    
    public static final String CHAT_HISTORY_KEY = "chat:history:";
    public static final Long CHAT_HISTORY_TTL = 7L;

    public static final String CHAT_MEMORY_KEY = "chat:memory:";

    // 布隆过滤器
    public static final String BLOOM_SHOP_KEY = "bloom:shop";

    // 热点缓存
    public static final String HOTSPOT_COUNTER_KEY = "hotspot:counter:";
    public static final String HOTSPOT_FLAG_KEY = "hotspot:flag:";
    public static final Long HOTSPOT_COUNTER_TTL = 60L; // 滑动窗口60秒

    // 秒杀令牌桶限流
    public static final String SECKILL_RATE_LIMIT_KEY = "seckill:rate_limit:";

    // 死信队列
    public static final String SECKILL_DLX_QUEUE = "seckill.dlx.queue";
    public static final String SECKILL_RETRY_QUEUE_PREFIX = "seckill.retry.queue.";

    // Outbox 消息表
    public static final int OUTBOX_MAX_RETRIES = 10;
    public static final long OUTBOX_SCAN_INTERVAL_MS = 30_000L; // 30秒扫描一次

    // 对账
    public static final long RECONCILIATION_INTERVAL_MS = 300_000L; // 5分钟
    public static final int RECONCILIATION_DIFF_THRESHOLD = 10; // 差异超过10时仅告警
}
