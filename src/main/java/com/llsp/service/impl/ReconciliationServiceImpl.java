package com.llsp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.llsp.entity.SeckillVoucher;
import com.llsp.entity.VoucherOrder;
import com.llsp.mapper.SeckillVoucherMapper;
import com.llsp.mapper.VoucherOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static com.llsp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * 秒杀库存对账补偿服务
 * 定期对比 Redis库存 vs DB库存 vs 实际订单数，以 DB 为准修正 Redis
 */
@Slf4j
@Service
public class ReconciliationServiceImpl {

    @Resource
    private SeckillVoucherMapper seckillVoucherMapper;

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 执行对账
     * @param diffThreshold 差异告警阈值（超过此值仅告警不自动修正）
     */
    public void reconcile(int diffThreshold) {
        log.info("====== 秒杀库存对账开始 ======");

        // 1. 查询所有有效期内的秒杀券
        LocalDateTime now = LocalDateTime.now();
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

        for (SeckillVoucher sv : activeVouchers) {
            try {
                Long voucherId = sv.getVoucherId();
                int dbStock = sv.getStock();
                int initialStock = getInitialStock(voucherId, dbStock);

                // 2. 从 DB 统计实际订单数
                Long actualOrderCount = voucherOrderMapper.selectCount(
                        new LambdaQueryWrapper<VoucherOrder>()
                                .eq(VoucherOrder::getVoucherId, voucherId)
                );

                // 3. 以 DB 为准计算应有库存
                int expectedStock = initialStock - actualOrderCount.intValue();
                if (expectedStock < 0) {
                    expectedStock = 0;
                }

                // 4. 读取 Redis 当前库存
                String stockKey = SECKILL_STOCK_KEY + voucherId;
                String redisStockStr = stringRedisTemplate.opsForValue().get(stockKey);
                int redisStock = redisStockStr != null ? Integer.parseInt(redisStockStr) : -1;

                // 5. 对比并修正
                if (redisStock == -1) {
                    // Redis 中无库存数据，从 DB 恢复
                    stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(expectedStock));
                    log.info("Redis库存恢复 - voucherId={}, dbStock={}, expectedStock={}",
                            voucherId, dbStock, expectedStock);
                    fixedCount++;
                } else if (redisStock != expectedStock) {
                    int diff = Math.abs(redisStock - expectedStock);
                    if (diff > diffThreshold) {
                        // 差异过大，仅告警不自动修正
                        log.error("【对账告警】库存差异过大 - voucherId={}, redisStock={}, expectedStock={}, " +
                                "dbStock={}, actualOrders={}, initialStock={}, diff={}",
                                voucherId, redisStock, expectedStock,
                                dbStock, actualOrderCount, initialStock, diff);
                        alertCount++;
                    } else {
                        // 差异在阈值内，自动修正
                        stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(expectedStock));
                        log.info("库存修正 - voucherId={}, redisStock={} -> expectedStock={}, diff={}",
                                voucherId, redisStock, expectedStock, diff);
                        fixedCount++;
                    }
                } else {
                    log.debug("库存一致 - voucherId={}, stock={}", voucherId, redisStock);
                }

                // 6. 清理 Redis 中已过期的下单用户集合（可选：清理 7 天前的数据）
                String orderKey = "seckill:order:" + voucherId;
                // 保留用户下单记录，用于一人一单判断（Lua 脚本依赖此数据）

            } catch (Exception e) {
                log.error("对账异常 - voucherId={}", sv.getVoucherId(), e);
            }
        }

        log.info("====== 秒杀库存对账结束 - 修正{}处, 告警{}处 ======", fixedCount, alertCount);
    }

    /**
     * 获取初始库存
     * 由于初始库存没有单独存储，这里通过 (当前DB库存 + 实际订单数) 反推
     * 实际场景建议在 tb_seckill_voucher 表中增加 initial_stock 字段
     */
    private int getInitialStock(Long voucherId, int currentDbStock) {
        Long orderCount = voucherOrderMapper.selectCount(
                new LambdaQueryWrapper<VoucherOrder>()
                        .eq(VoucherOrder::getVoucherId, voucherId)
        );
        return currentDbStock + orderCount.intValue();
    }

    /**
     * 检查 Redis 中的下单用户集合与实际订单是否一致
     * 清理幽灵记录（Redis 中有但 DB 中无对应订单的用户）
     */
    public void cleanGhostOrders(Long voucherId) {
        String orderKey = "seckill:order:" + voucherId;
        Set<String> redisUsers = stringRedisTemplate.opsForSet().members(orderKey);
        if (redisUsers == null || redisUsers.isEmpty()) {
            return;
        }

        for (String userIdStr : redisUsers) {
            Long userId = Long.valueOf(userIdStr);
            Long count = voucherOrderMapper.selectCount(
                    new LambdaQueryWrapper<VoucherOrder>()
                            .eq(VoucherOrder::getVoucherId, voucherId)
                            .eq(VoucherOrder::getUserId, userId)
            );
            if (count == 0) {
                // Redis 中有记录但 DB 中无订单，清理幽灵记录
                stringRedisTemplate.opsForSet().remove(orderKey, userIdStr);
                log.warn("清理幽灵下单记录 - voucherId={}, userId={}", voucherId, userId);
            }
        }
    }
}
