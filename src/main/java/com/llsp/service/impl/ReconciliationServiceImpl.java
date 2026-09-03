package com.llsp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.llsp.entity.SeckillVoucher;
import com.llsp.entity.VoucherOrder;
import com.llsp.mapper.SeckillVoucherMapper;
import com.llsp.mapper.VoucherOrderMapper;
import com.llsp.service.IVoucherOrderService;
import com.llsp.utils.RedisIdWorker;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.llsp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * 秒杀库存对账补偿服务
 * 定期对比 Redis库存 vs DB库存 vs 实际订单数，以 DB 为准修正 Redis
 */
@Slf4j
@Service
public class ReconciliationServiceImpl {

    /**
     * 业务时区。JDBC 连接串为 serverTimezone=UTC，若用 JVM 默认时区，
     * 部署到 UTC 容器后时间窗判定会偏 8 小时，故显式指定。
     */
    private static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    @Resource
    private SeckillVoucherMapper seckillVoucherMapper;

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private RedissonClient redissonClient;

    /**
     * 执行对账
     * @param diffThreshold 差异告警阈值（超过此值仅告警不自动修正）
     */
    public void reconcile(int diffThreshold) {
        log.info("====== 秒杀库存对账开始 ======");

        // 1. 查询所有有效期内的秒杀券
        LocalDateTime now = LocalDateTime.now(BIZ_ZONE);
        List<SeckillVoucher> activeVouchers = seckillVoucherMapper.selectList(
                new LambdaQueryWrapper<SeckillVoucher>()
                        .le(SeckillVoucher::getBeginTime, now)
                        .ge(SeckillVoucher::getEndTime, now)
        );

        if (activeVouchers.isEmpty()) {
            log.info("没有有效期内的秒杀券，对账结束");
            return;
        }

        int fixedCount = 0;
        int alertCount = 0;
        int compensatedTotal = 0;
        int failedTotal = 0;

        for (SeckillVoucher sv : activeVouchers) {
            try {
                Long voucherId = sv.getVoucherId();

                // 2. 补单：Redis 下单集合与 DB 订单的差集（扣了库存但订单没落库的用户）
                int[] result = compensateGhostOrders(voucherId);
                compensatedTotal += result[0];
                failedTotal += result[1];

                // 3. 以 DB 为准修正 Redis 库存（补单后 DB 库存已自洽）
                SeckillVoucher latest = seckillVoucherMapper.selectById(voucherId);
                int dbStock = latest.getStock();
                String stockKey = SECKILL_STOCK_KEY + voucherId;
                String redisStockStr = stringRedisTemplate.opsForValue().get(stockKey);

                // key 不存在 / 值非法 = "无数据或脏数据"，与"数值不一致"性质不同，
                // 必须无条件从 DB 恢复。若走下面的 diff 阈值判断，diff = dbStock + 1 会
                // 远大于阈值从而只告警不修正，导致库存 key 丢失后永久无法自愈。
                Integer redisStock = parseStock(redisStockStr);
                if (redisStock == null) {
                    stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(dbStock));
                    log.warn("Redis库存不可用，已从DB恢复 - voucherId={}, redisValue={}, dbStock={}",
                            voucherId, redisStockStr, dbStock);
                    fixedCount++;
                    continue;
                }

                if (redisStock != dbStock) {
                    int diff = Math.abs(redisStock - dbStock);
                    if (diff > diffThreshold) {
                        log.error("【对账告警】库存差异过大 - voucherId={}, redisStock={}, dbStock={}, diff={}",
                                voucherId, redisStock, dbStock, diff);
                        alertCount++;
                    } else {
                        stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(dbStock));
                        log.info("库存修正 - voucherId={}, redisStock={} -> dbStock={}, diff={}",
                                voucherId, redisStock, dbStock, diff);
                        fixedCount++;
                    }
                } else {
                    log.debug("库存一致 - voucherId={}, stock={}", voucherId, redisStock);
                }

            } catch (Exception e) {
                log.error("对账异常 - voucherId={}", sv.getVoucherId(), e);
            }
        }

        log.info("====== 秒杀库存对账结束 - 补单{}单(失败{}), 修正{}处, 告警{}处 ======",
                compensatedTotal, failedTotal, fixedCount, alertCount);
        if (failedTotal > 0) {
            log.error("对账补单存在 {} 条失败，需人工介入", failedTotal);
        }
    }

    /**
     * 解析 Redis 中的库存值
     * @return 合法库存值；key 不存在或值非法时返回 null
     */
    private Integer parseStock(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 补单：Redis 下单集合与 DB 订单的差集（扣了库存但订单没落库的用户）
     * 复用 createVoucherOrder（扣 DB 库存 + 建单），幂等由 uk_user_voucher 唯一索引兜底。
     * 与正常消费路径共用 lock:order:{userId}，避免补单与消费并发导致多扣库存再回滚。
     * @return int[2]：[0]=成功补单数，[1]=补单失败数（库存不足/并发已下单/加锁失败）
     */
    private int[] compensateGhostOrders(Long voucherId) {
        String orderKey = "seckill:order:" + voucherId;
        Set<String> redisUsers = stringRedisTemplate.opsForSet().members(orderKey);
        if (redisUsers == null || redisUsers.isEmpty()) {
            return new int[]{0, 0};
        }

        int compensated = 0;
        int failed = 0;
        for (String userIdStr : redisUsers) {
            Long userId = Long.valueOf(userIdStr);
            Long count = voucherOrderMapper.selectCount(
                    new LambdaQueryWrapper<VoucherOrder>()
                            .eq(VoucherOrder::getVoucherId, voucherId)
                            .eq(VoucherOrder::getUserId, userId)
            );
            if (count > 0) {
                // 已有订单，无需补单
                continue;
            }

            RLock lock = redissonClient.getLock("lock:order:" + userId);
            boolean locked = false;
            try {
                locked = lock.tryLock(1, TimeUnit.SECONDS);
                if (!locked) {
                    // 订单正在被消费，跳过本次对账，下一轮再试，不阻塞
                    log.warn("补单跳过，订单处理中 - voucherId={}, userId={}", voucherId, userId);
                    failed++;
                    continue;
                }

                VoucherOrder order = new VoucherOrder();
                order.setId(redisIdWorker.nextId("order"));
                order.setUserId(userId);
                order.setVoucherId(voucherId);
                if (voucherOrderService.createVoucherOrder(order)) {
                    compensated++;
                    log.warn("对账补单成功 - voucherId={}, userId={}", voucherId, userId);
                } else {
                    failed++;
                    log.error("对账补单失败（库存不足或并发已下单）- voucherId={}, userId={}", voucherId, userId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failed++;
                log.error("补单加锁被中断 - voucherId={}, userId={}", voucherId, userId, e);
            } finally {
                if (locked) {
                    lock.unlock();
                }
            }
        }
        return new int[]{compensated, failed};
    }
}
