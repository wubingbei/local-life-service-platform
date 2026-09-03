package com.llsp.listeners;

import com.llsp.entity.VoucherOrder;
import com.llsp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class SeckillListener {

    @Resource
    private IVoucherOrderService voucherOrderService;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private Jackson2JsonMessageConverter messageConverter;

    @Value("${llsp.feature.dead-letter-enabled:true}")
    private boolean deadLetterEnabled;

    /**
     * 最大重试次数（含首次消费）
     */
    private static final int MAX_RETRIES = 3;

    /**
     * 监听秒杀订单队列
     * 队列及绑定由 DeadLetterConfig 以 @Bean 方式声明
     */
    @RabbitListener(queues = "seckill.queue")
    public void listenSeckillOrder(Message message) {
        VoucherOrder voucherOrder = null;
        try {
            voucherOrder = (VoucherOrder) messageConverter.fromMessage(message);
        } catch (Exception e) {
            log.error("消息解析失败 - message={}", new String(message.getBody()), e);
            // 毒消息无法重试：先入死信队列留痕，再显式拒收不 requeue，避免无限重试死循环
            sendRawToDeadLetter(message);
            throw new AmqpRejectAndDontRequeueException("消息解析失败", e);
        }

        try {
            handleVoucherOrder(voucherOrder);
            // 处理成功，正常返回（消息 ACK）
        } catch (Exception e) {
            log.error("订单处理失败 - orderId={}, userId={}, voucherId={}",
                    voucherOrder.getId(), voucherOrder.getUserId(), voucherOrder.getVoucherId(), e);

            if (!deadLetterEnabled) {
                // DLX 未启用：显式记录丢弃，不留静默黑洞
                log.error("死信队列未启用，订单消息被丢弃 - orderId={}", voucherOrder.getId());
                return;
            }

            // 读取当前重试次数，决定路由到哪一级重试队列
            int retryCount = getRetryCount(message);
            if (retryCount >= MAX_RETRIES) {
                // 超过最大重试次数，路由到死信队列
                log.error("重试次数耗尽 ({}次)，订单进入死信队列 - orderId={}",
                        retryCount, voucherOrder.getId());
                sendToRetryQueue(voucherOrder, "seckill.dlx", retryCount);
            } else {
                // 路由到下一级重试队列（指数退避）
                String nextRetryKey = "seckill.retry." + (retryCount + 1);
                log.warn("第{}次重试失败，路由到重试队列 {} - orderId={}",
                        retryCount, nextRetryKey, voucherOrder.getId());
                sendToRetryQueue(voucherOrder, nextRetryKey, retryCount);
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // 创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 获取锁（短等待，Watchdog 自动续期；失败抛异常进重试队列而非静默丢单）
        boolean isLock;
        try {
            isLock = lock.tryLock(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("获取订单锁被中断", e);
        }
        if (!isLock) {
            log.warn("用户 {} 上一个订单还在处理中，进入重试", userId);
            throw new IllegalStateException("用户订单处理中，稍后重试");
        }
        try {
            boolean created = voucherOrderService.createVoucherOrder(voucherOrder);
            if (!created) {
                log.warn("订单未创建（重复下单或库存不足）- orderId={}, userId={}",
                        voucherOrder.getId(), voucherOrder.getUserId());
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 从消息头中读取重试次数
     * 首次消费时 header 不存在，返回 0，使首次失败进 seckill.retry.1（10s），
     * 链路为 10s → 30s → 120s → 死信，重试 3 次与 MAX_RETRIES 一致。
     */
    private int getRetryCount(Message message) {
        MessageProperties props = message.getMessageProperties();
        Object count = props.getHeaders().get("x-retry-count");
        if (count instanceof Integer) {
            return (Integer) count;
        }
        if (count instanceof Long) {
            return ((Long) count).intValue();
        }
        return 0; // 默认首次消费
    }

    /**
     * 发送订单到指定的重试队列或死信队列
     * @param voucherOrder 订单信息
     * @param routingKey   目标路由键
     * @param currentRetry 当前已重试次数
     */
    private void sendToRetryQueue(VoucherOrder voucherOrder, String routingKey, int currentRetry) {
        try {
            int nextRetry = currentRetry + 1;
            rabbitTemplate.convertAndSend("seckill.dlx", routingKey, voucherOrder, msg -> {
                MessageProperties props = msg.getMessageProperties();
                props.setHeader("x-retry-count", nextRetry);
                return msg;
            });
            log.info("订单已路由 - orderId={}, routingKey={}, nextRetryCount={}",
                    voucherOrder.getId(), routingKey, nextRetry);
        } catch (Exception e) {
            log.error("路由到重试队列失败 - orderId={}, routingKey={}",
                    voucherOrder.getId(), routingKey, e);
        }
    }

    /**
     * 原样转发毒消息到死信队列（保留原始字节，不做反序列化）
     * 队列级 DLX 已移除，reject 不 requeue 的消息无 DLX 可去会直接丢弃，
     * 因此在 reject 前手动投递，恢复死信队列的兜底观测能力。
     */
    private void sendRawToDeadLetter(Message message) {
        if (!deadLetterEnabled) {
            return;
        }
        try {
            rabbitTemplate.send("seckill.dlx", "seckill.dlx", message);
            log.warn("毒消息已入死信队列 - messageId={}",
                    message.getMessageProperties().getMessageId());
        } catch (Exception ex) {
            log.error("毒消息入死信队列失败，消息将被丢弃", ex);
        }
    }
}
