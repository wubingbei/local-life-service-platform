package com.llsp.aop;

import com.llsp.annotation.RateLimit;
import com.llsp.dto.Result;
import com.llsp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 令牌桶限流 AOP 切面
 * 拦截 @RateLimit 注解的方法，通过 Redisson RRateLimiter 进行流量控制
 * Fail-open 策略：Redis 异常时直接放行，保证可用性
 */
@Slf4j
@Aspect
@Component
public class RateLimitAspect {

    @Resource
    private RedissonClient redissonClient;

    @Value("${llsp.feature.rate-limiter-enabled:true}")
    private boolean rateLimiterEnabled;

    private static final String RATE_LIMIT_KEY_PREFIX = "seckill:rate_limit:";

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        if (!rateLimiterEnabled) {
            return joinPoint.proceed();
        }

        String globalKey = resolveKey(joinPoint, rateLimit);
        // 1. 全局限流：保护系统整体
        if (!tryAcquire(globalKey, rateLimit.rate(), rateLimit)) {
            log.warn("全局限流触发 - key={}, rate={}/{}s", globalKey, rateLimit.rate(), rateLimit.interval());
            return Result.fail(rateLimit.message());
        }

        // 2. 每用户限流：防单用户/脚本刷
        if (rateLimit.perUserRate() > 0) {
            String userKey = globalKey + ":" + resolveUserId();
            if (!tryAcquire(userKey, rateLimit.perUserRate(), rateLimit)) {
                log.warn("每用户限流触发 - key={}, rate={}/{}s", userKey, rateLimit.perUserRate(), rateLimit.interval());
                return Result.fail("操作过于频繁，请稍后再试");
            }
        }

        return joinPoint.proceed();
    }

    /**
     * 解析限流 key
     * 优先使用注解指定的 key，否则使用方法全限定名作为默认 key
     */
    private String resolveKey(ProceedingJoinPoint joinPoint, RateLimit rateLimit) {
        if (!rateLimit.key().isEmpty()) {
            return RATE_LIMIT_KEY_PREFIX + rateLimit.key();
        }
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String methodName = signature.getMethod().getDeclaringClass().getName()
                + "." + signature.getMethod().getName();
        return RATE_LIMIT_KEY_PREFIX + methodName;
    }

    /**
     * 尝试获取令牌
     * Redis 异常时返回 true（fail-open 策略）
     */
    private boolean tryAcquire(String key, double rate, RateLimit rateLimit) {
        try {
            RRateLimiter limiter = redissonClient.getRateLimiter(key);
            // 首次使用时初始化限流器
            // rate 为 double（支持 perUserRate=0.5 这类配置），强转 long 会截断成 0 导致全量拒绝，
            // 故向上取整到至少 1。
            if (!limiter.isExists()) {
                limiter.trySetRate(RateType.OVERALL,
                        Math.max(1L, Math.round(rate)),
                        rateLimit.interval(),
                        toRateIntervalUnit(rateLimit.timeUnit()));
            }
            return limiter.tryAcquire();
        } catch (Exception e) {
            // Fail-open: Redis 不可用时放行，保证服务可用
            log.error("令牌桶限流异常，降级放行 - key={}", key, e);
            return true;
        }
    }

    /**
     * 获取当前用户 id，未登录时降级为 anonymous（避免 NPE）
     */
    private String resolveUserId() {
        try {
            if (UserHolder.getUser() != null && UserHolder.getUser().getId() != null) {
                return String.valueOf(UserHolder.getUser().getId());
            }
        } catch (Exception e) {
            // 忽略，降级为 anonymous
        }
        return "anonymous";
    }

    /**
     * 将 TimeUnit 转换为 Redisson 的 RateIntervalUnit
     */
    private RateIntervalUnit toRateIntervalUnit(java.util.concurrent.TimeUnit timeUnit) {
        return switch (timeUnit) {
            case MILLISECONDS -> RateIntervalUnit.MILLISECONDS;
            case SECONDS -> RateIntervalUnit.SECONDS;
            case MINUTES -> RateIntervalUnit.MINUTES;
            case HOURS -> RateIntervalUnit.HOURS;
            case DAYS -> RateIntervalUnit.DAYS;
            default -> RateIntervalUnit.SECONDS;
        };
    }
}
