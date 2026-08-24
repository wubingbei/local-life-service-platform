package com.llsp.service.impl;

import cn.hutool.core.lang.UUID;
import cn.hutool.json.JSONUtil;
import com.llsp.entity.OutboxMessage;
import com.llsp.mapper.OutboxMessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;

/**
 * Outbox 消息投递服务
 * - 封装可靠消息发送（先写 Outbox 再发 MQ）
 * - 定时扫描未确认消息并补发
 * - 指数退避重试
 */
@Slf4j
@Service
public class OutboxRelayService {

    @Resource
    private OutboxMessageMapper outboxMessageMapper;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Value("${llsp.feature.outbox-enabled:true}")
    private boolean outboxEnabled;

    /**
     * 每次扫描的最大消息数
     */
    private static final int BATCH_SIZE = 100;

    /**
     * 指数退避基础延迟（毫秒）
     */
    private static final long BASE_DELAY_MS = 10_000L;

    /**
     * 最大延迟（毫秒）
     */
    private static final long MAX_DELAY_MS = 600_000L;

    /**
     * 发送消息并写入 Outbox（可靠投递入口）
     * 先写 Outbox 记录（独立事务），再发送 MQ
     *
     * @param exchange   RabbitMQ 交换机
     * @param routingKey RabbitMQ 路由键
     * @param payload    消息体（将序列化为 JSON）
     * @param orderId    关联的订单 ID
     */
    public void sendWithOutbox(String exchange, String routingKey, Object payload, Long orderId) {
        String messageId = UUID.fastUUID().toString();
        String payloadJson = JSONUtil.toJsonStr(payload);

        if (!outboxEnabled) {
            // 功能关闭时直接发送，保持原有行为
            rabbitTemplate.convertAndSend(exchange, routingKey, payload);
            return;
        }

        // 1. 先写 Outbox 记录（独立事务，确保消息先持久化）
        OutboxMessage outbox = new OutboxMessage();
        outbox.setMessageId(messageId);
        outbox.setExchange(exchange);
        outbox.setRoutingKey(routingKey);
        outbox.setPayload(payloadJson);
        outbox.setStatus(OutboxMessage.STATUS_PENDING);
        outbox.setRetryCount(0);
        outbox.setMaxRetries(10);
        outbox.setNextRetryTime(LocalDateTime.now());
        outbox.setOrderId(orderId);
        outbox.setCreateTime(LocalDateTime.now());
        outbox.setUpdateTime(LocalDateTime.now());

        try {
            saveOutbox(outbox);
        } catch (Exception e) {
            // Outbox 写入失败，降级为直接发送 MQ
            log.error("Outbox 写入失败，降级为直接发送 MQ - messageId={}", messageId, e);
            rabbitTemplate.convertAndSend(exchange, routingKey, payload);
            return;
        }

        // 2. 发送 MQ（带 Publisher Confirm 回调）
        CorrelationData correlationData = new CorrelationData(messageId);
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, payload, correlationData);
            // Publisher Confirm 成功后在回调中标记为已发送
            // 这里先乐观认为会成功，失败由回调 + 定时任务兜底
        } catch (Exception e) {
            log.error("MQ 发送异常 - messageId={}", messageId, e);
            // 不抛异常，由定时任务补发
        }
    }

    /**
     * 保存 Outbox 记录（独立事务）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void saveOutbox(OutboxMessage outbox) {
        outboxMessageMapper.insert(outbox);
    }

    /**
     * 定时扫描 Outbox 并补发未确认的消息（每 30 秒执行一次）
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 10_000)
    public void relayPendingMessages() {
        if (!outboxEnabled) {
            return;
        }

        try {
            LocalDateTime now = LocalDateTime.now();
            var pendingMessages = outboxMessageMapper.selectPendingMessages(now, BATCH_SIZE);

            if (pendingMessages.isEmpty()) {
                return;
            }

            log.debug("扫描到 {} 条待补发的 Outbox 消息", pendingMessages.size());

            for (OutboxMessage msg : pendingMessages) {
                processRelay(msg);
            }
        } catch (Exception e) {
            log.error("Outbox 定时补发任务异常", e);
        }
    }

    /**
     * 处理单条消息的补发
     */
    private void processRelay(OutboxMessage msg) {
        try {
            // 超过最大重试次数，标记为失败
            if (msg.getRetryCount() >= msg.getMaxRetries()) {
                outboxMessageMapper.markAsFailed(msg.getId(),
                        "超过最大重试次数 " + msg.getMaxRetries());
                log.error("Outbox 消息重试耗尽 - messageId={}, orderId={}, retryCount={}",
                        msg.getMessageId(), msg.getOrderId(), msg.getRetryCount());
                return;
            }

            // 重新发送 MQ
            Object payload = JSONUtil.parse(msg.getPayload());
            CorrelationData correlationData = new CorrelationData(msg.getMessageId());
            rabbitTemplate.convertAndSend(msg.getExchange(), msg.getRoutingKey(),
                    payload, correlationData);

            // 乐观标记为已发送（确认失败由下次定时任务兜底）
            outboxMessageMapper.markAsSent(msg.getId());

            log.info("Outbox 补发成功 - messageId={}, orderId={}", msg.getMessageId(), msg.getOrderId());

        } catch (Exception e) {
            // 发送失败，更新重试信息（指数退避）
            long delay = calculateBackoffDelay(msg.getRetryCount() + 1);
            LocalDateTime nextRetry = LocalDateTime.now().plusSeconds(delay / 1000);
            outboxMessageMapper.updateRetryInfo(msg.getId(), nextRetry);

            log.warn("Outbox 补发失败 - messageId={}, retryCount={}, nextRetry={}, error={}",
                    msg.getMessageId(), msg.getRetryCount() + 1, nextRetry, e.getMessage());
        }
    }

    /**
     * 计算指数退避延迟
     * 公式：min(10s * 2^retryCount, 600s)
     */
    long calculateBackoffDelay(int retryCount) {
        long delay = BASE_DELAY_MS * (1L << Math.min(retryCount, 10));
        return Math.min(delay, MAX_DELAY_MS);
    }
}
