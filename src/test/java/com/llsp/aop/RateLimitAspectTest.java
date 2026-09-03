package com.llsp.aop;

import com.llsp.annotation.RateLimit;
import com.llsp.dto.Result;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RateLimitAspectTest {

    private RateLimitAspect newAspect(RedissonClient redissonClient) {
        RateLimitAspect aspect = new RateLimitAspect();
        ReflectionTestUtils.setField(aspect, "redissonClient", redissonClient);
        ReflectionTestUtils.setField(aspect, "rateLimiterEnabled", true);
        return aspect;
    }

    private RateLimit mockRateLimit() {
        RateLimit rateLimit = mock(RateLimit.class);
        when(rateLimit.key()).thenReturn("test");
        when(rateLimit.rate()).thenReturn(100.0);
        when(rateLimit.interval()).thenReturn(1L);
        when(rateLimit.timeUnit()).thenReturn(TimeUnit.SECONDS);
        when(rateLimit.perUserRate()).thenReturn(0.0);
        when(rateLimit.message()).thenReturn("限流");
        return rateLimit;
    }

    @Test
    void around_blocksWhenGlobalLimitExceeded() throws Throwable {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RRateLimiter limiter = mock(RRateLimiter.class);
        when(redissonClient.getRateLimiter(anyString())).thenReturn(limiter);
        when(limiter.isExists()).thenReturn(true);
        when(limiter.tryAcquire()).thenReturn(false);  // 全局限流触发

        RateLimitAspect aspect = newAspect(redissonClient);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        RateLimit rateLimit = mockRateLimit();

        Object result = aspect.around(joinPoint, rateLimit);

        assertTrue(result instanceof Result);
        assertFalse(((Result) result).getSuccess());
        verify(joinPoint, never()).proceed();
    }

    @Test
    void around_blocksWhenPerUserLimitExceeded() throws Throwable {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RRateLimiter globalLimiter = mock(RRateLimiter.class);
        RRateLimiter userLimiter = mock(RRateLimiter.class);
        when(redissonClient.getRateLimiter("seckill:rate_limit:test")).thenReturn(globalLimiter);
        when(redissonClient.getRateLimiter("seckill:rate_limit:test:anonymous")).thenReturn(userLimiter);
        when(globalLimiter.isExists()).thenReturn(true);
        when(globalLimiter.tryAcquire()).thenReturn(true);   // 全局通过
        when(userLimiter.isExists()).thenReturn(true);
        when(userLimiter.tryAcquire()).thenReturn(false);    // 每用户触发

        RateLimitAspect aspect = newAspect(redissonClient);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        RateLimit rateLimit = mockRateLimit();
        when(rateLimit.perUserRate()).thenReturn(2.0);

        Object result = aspect.around(joinPoint, rateLimit);

        assertTrue(result instanceof Result);
        assertFalse(((Result) result).getSuccess());
        verify(joinPoint, never()).proceed();
    }
}
