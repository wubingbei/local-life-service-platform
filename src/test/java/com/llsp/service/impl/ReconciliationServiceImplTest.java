package com.llsp.service.impl;

import com.llsp.entity.SeckillVoucher;
import com.llsp.entity.VoucherOrder;
import com.llsp.mapper.SeckillVoucherMapper;
import com.llsp.mapper.VoucherOrderMapper;
import com.llsp.service.IVoucherOrderService;
import com.llsp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReconciliationServiceImplTest {

    private ReconciliationServiceImpl newService(Object... mocks) {
        ReconciliationServiceImpl service = new ReconciliationServiceImpl();
        for (int i = 0; i < mocks.length; i += 2) {
            ReflectionTestUtils.setField(service, (String) mocks[i], mocks[i + 1]);
        }
        return service;
    }

    private SeckillVoucher activeVoucher(Long id, int stock) {
        SeckillVoucher sv = new SeckillVoucher();
        sv.setVoucherId(id);
        sv.setStock(stock);
        sv.setBeginTime(LocalDateTime.now().minusHours(1));
        sv.setEndTime(LocalDateTime.now().plusHours(1));
        return sv;
    }

    /**
     * P0：Redis 库存 key 丢失（get 返回 null），dbStock=99，应无条件从 DB 恢复，
     * 不能因 diff=100>阈值而只告警不修正（那是原 bug，会导致永久无法自愈）。
     */
    @Test
    void reconcile_restoresKeyWhenMissing() {
        ReconciliationServiceImpl service = newService(
                "seckillVoucherMapper", mock(SeckillVoucherMapper.class),
                "voucherOrderMapper", mock(VoucherOrderMapper.class),
                "stringRedisTemplate", mock(StringRedisTemplate.class),
                "redisIdWorker", mock(RedisIdWorker.class),
                "voucherOrderService", mock(IVoucherOrderService.class),
                "redissonClient", mock(RedissonClient.class));
        SeckillVoucherMapper seckillVoucherMapper = getField(service, "seckillVoucherMapper");
        StringRedisTemplate stringRedisTemplate = getField(service, "stringRedisTemplate");

        SeckillVoucher sv = activeVoucher(1L, 99);
        when(seckillVoucherMapper.selectList(any())).thenReturn(List.of(sv));
        when(seckillVoucherMapper.selectById(1L)).thenReturn(sv);

        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("seckill:stock:1")).thenReturn(null);
        // 补单路径：集合为空，不触发补单
        SetOperations<String, String> setOps = mock(SetOperations.class);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(anyString())).thenReturn(null);

        service.reconcile(10);

        // 关键断言：无条件写回 DB 库存，而不是只告警
        verify(valueOps).set("seckill:stock:1", "99");
    }

    /**
     * P0 边界：Redis 库存值被写坏（非数字），同样应从 DB 恢复，且不抛 NumberFormatException
     */
    @Test
    void reconcile_restoresKeyWhenValueCorrupted() {
        ReconciliationServiceImpl service = newService(
                "seckillVoucherMapper", mock(SeckillVoucherMapper.class),
                "voucherOrderMapper", mock(VoucherOrderMapper.class),
                "stringRedisTemplate", mock(StringRedisTemplate.class),
                "redisIdWorker", mock(RedisIdWorker.class),
                "voucherOrderService", mock(IVoucherOrderService.class),
                "redissonClient", mock(RedissonClient.class));
        SeckillVoucherMapper seckillVoucherMapper = getField(service, "seckillVoucherMapper");
        StringRedisTemplate stringRedisTemplate = getField(service, "stringRedisTemplate");

        SeckillVoucher sv = activeVoucher(2L, 50);
        when(seckillVoucherMapper.selectList(any())).thenReturn(List.of(sv));
        when(seckillVoucherMapper.selectById(2L)).thenReturn(sv);

        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("seckill:stock:2")).thenReturn("abc");
        SetOperations<String, String> setOps = mock(SetOperations.class);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(anyString())).thenReturn(null);

        // 不应抛异常（原实现 Integer.parseInt 会抛并被外层 catch 吞掉，中断整券对账）
        assertDoesNotThrow(() -> service.reconcile(10));
        verify(valueOps).set("seckill:stock:2", "50");
    }

    /**
     * P1-2：createVoucherOrder 返回 false（库存不足/并发已下单）时，
     * compensated 不应计数，failed 应计数，避免日志谎报补单成功。
     */
    @Test
    void compensateGhostOrders_doesNotCountFailedCreates() {
        ReconciliationServiceImpl service = newService(
                "seckillVoucherMapper", mock(SeckillVoucherMapper.class),
                "voucherOrderMapper", mock(VoucherOrderMapper.class),
                "stringRedisTemplate", mock(StringRedisTemplate.class),
                "redisIdWorker", mock(RedisIdWorker.class),
                "voucherOrderService", mock(IVoucherOrderService.class),
                "redissonClient", mock(RedissonClient.class));
        StringRedisTemplate stringRedisTemplate = getField(service, "stringRedisTemplate");
        VoucherOrderMapper voucherOrderMapper = getField(service, "voucherOrderMapper");
        RedisIdWorker redisIdWorker = getField(service, "redisIdWorker");
        IVoucherOrderService voucherOrderService = getField(service, "voucherOrderService");
        RedissonClient redissonClient = getField(service, "redissonClient");

        SetOperations<String, String> setOps = mock(SetOperations.class);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(anyString())).thenReturn(Set.of("100", "200"));
        when(voucherOrderMapper.selectCount(any())).thenReturn(0L);  // 两个都是差集
        when(redisIdWorker.nextId("order")).thenReturn(999L, 1000L);
        when(voucherOrderService.createVoucherOrder(any())).thenReturn(false);  // 全部失败
        RLock lock = mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        try {
            when(lock.tryLock(1L, TimeUnit.SECONDS)).thenReturn(true);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        int[] result = (int[]) ReflectionTestUtils.invokeMethod(service, "compensateGhostOrders", 1L);

        assertEquals(0, result[0], "失败不应计入 compensated");
        assertEquals(2, result[1], "失败应计入 failed");
        verify(voucherOrderService, times(2)).createVoucherOrder(any(VoucherOrder.class));
    }

    /**
     * P1-2 正向：createVoucherOrder 返回 true 时才计入 compensated
     */
    @Test
    void compensateGhostOrders_countsOnlySuccessfulCreates() {
        ReconciliationServiceImpl service = newService(
                "seckillVoucherMapper", mock(SeckillVoucherMapper.class),
                "voucherOrderMapper", mock(VoucherOrderMapper.class),
                "stringRedisTemplate", mock(StringRedisTemplate.class),
                "redisIdWorker", mock(RedisIdWorker.class),
                "voucherOrderService", mock(IVoucherOrderService.class),
                "redissonClient", mock(RedissonClient.class));
        StringRedisTemplate stringRedisTemplate = getField(service, "stringRedisTemplate");
        VoucherOrderMapper voucherOrderMapper = getField(service, "voucherOrderMapper");
        RedisIdWorker redisIdWorker = getField(service, "redisIdWorker");
        IVoucherOrderService voucherOrderService = getField(service, "voucherOrderService");
        RedissonClient redissonClient = getField(service, "redissonClient");

        SetOperations<String, String> setOps = mock(SetOperations.class);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(anyString())).thenReturn(Set.of("100", "200"));
        when(voucherOrderMapper.selectCount(any())).thenReturn(0L);
        when(redisIdWorker.nextId("order")).thenReturn(999L, 1000L);
        // 一成一败
        when(voucherOrderService.createVoucherOrder(any())).thenReturn(true, false);
        RLock lock = mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        try {
            when(lock.tryLock(1L, TimeUnit.SECONDS)).thenReturn(true);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        int[] result = (int[]) ReflectionTestUtils.invokeMethod(service, "compensateGhostOrders", 1L);

        assertEquals(1, result[0]);
        assertEquals(1, result[1]);
    }

    @SuppressWarnings("unchecked")
    private <T> T getField(Object target, String field) {
        return (T) ReflectionTestUtils.getField(target, field);
    }
}
