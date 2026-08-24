package com.llsp.job;

import com.llsp.service.impl.ReconciliationServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 秒杀库存定时对账任务
 * 每 5 分钟执行一次，确保 Redis 库存与 DB 库存最终一致
 */
@Slf4j
@Component
public class StockReconciliationJob {

    @Resource
    private ReconciliationServiceImpl reconciliationService;

    @Value("${llsp.feature.reconciliation-enabled:true}")
    private boolean reconciliationEnabled;

    /**
     * 差异告警阈值：Redis 与 DB 差异超过此值时仅告警，不自动修正
     */
    private static final int DIFF_THRESHOLD = 10;

    /**
     * 每 5 分钟执行一次对账
     * cron: 秒 分 时 日 月 周
     */
    @Scheduled(cron = "0 */5 * * * ?")
    public void reconcileStocks() {
        if (!reconciliationEnabled) {
            return;
        }

        log.debug("定时对账任务触发");
        try {
            reconciliationService.reconcile(DIFF_THRESHOLD);
        } catch (Exception e) {
            log.error("定时对账任务执行异常", e);
        }
    }
}
