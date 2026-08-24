package com.llsp.listeners;

import com.llsp.entity.VoucherOrder;
import com.llsp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

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
            log.error("消息解析失败，直接拒绝 - message={}", new String(message.getBody()), e);
            // 消息格式错误，无法重试，直接丢弃（或入死信）
            throw new RuntimeException("消息解析失败", e);
        }

        try {
            handleVoucherOrder(voucherOrder);
            // 处理成功，正常返回（消息 ACK）
        } catch (Exception e) {
            log.error("订单处理失败 - orderId={}, userId={}, voucherId={}",
                    voucherOrder.getId(), voucherOrder.getUserId(), voucherOrder.getVoucherId(), e);

            if (!deadLetterEnabled) {
                return; // DLX 未启用，静默丢弃
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
        // 获取锁
        boolean isLock = lock.tryLock();
        if (!isLock) {
            // 失败，说明该用户的上一个订单还在处理
            log.warn("用户 {} 上一个订单还在处理中，拒绝重复下单", userId);
            return;
        }
        try {
            voucherOrderService.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 从消息头中读取重试次数
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
        return 1; // 默认首次重试
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
}
