package com.llsp.config;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 死信队列（DLX）+ 指数退避重试配置
 *
 * 架构：
 *   seckill.queue (工作队列)
 *     ↓ 消费失败
 *   seckill.retry.queue.1 (TTL=10s)  → 过期 → seckill.queue
 *     ↓ 消费失败
 *   seckill.retry.queue.2 (TTL=30s)  → 过期 → seckill.queue
 *     ↓ 消费失败
 *   seckill.retry.queue.3 (TTL=120s) → 过期 → seckill.queue
 *     ↓ 消费失败
 *   seckill.dlx.queue (死信队列，告警 + 人工处理)
 */
@Configuration
public class DeadLetterConfig {

    @Value("${llsp.feature.dead-letter-enabled:true}")
    private boolean deadLetterEnabled;

    // ==================== 交换机 ====================

    @Bean
    public TopicExchange seckillExchange() {
        return new TopicExchange("seckill.topic", true, false);
    }

    @Bean
    public DirectExchange seckillDlxExchange() {
        return new DirectExchange("seckill.dlx", true, false);
    }

    // ==================== 死信队列（最终兜底） ====================

    @Bean
    public Queue seckillDlxQueue() {
        return QueueBuilder.durable("seckill.dlx.queue").build();
    }

    @Bean
    public Binding seckillDlxBinding() {
        return BindingBuilder.bind(seckillDlxQueue())
                .to(seckillDlxExchange())
                .with("seckill.dlx");
    }

    // ==================== 三级重试延迟队列 ====================

    @Bean
    public Queue seckillRetryQueue1() {
        return QueueBuilder.durable("seckill.retry.queue.1")
                .deadLetterExchange("seckill.topic")    // 过期后路由回工作队列
                .deadLetterRoutingKey("seckill.success")
                .ttl(10_000)  // 10 秒延迟
                .build();
    }

    @Bean
    public Binding seckillRetryBinding1() {
        return BindingBuilder.bind(seckillRetryQueue1())
                .to(seckillDlxExchange())
                .with("seckill.retry.1");
    }

    @Bean
    public Queue seckillRetryQueue2() {
        return QueueBuilder.durable("seckill.retry.queue.2")
                .deadLetterExchange("seckill.topic")
                .deadLetterRoutingKey("seckill.success")
                .ttl(30_000)  // 30 秒延迟
                .build();
    }

    @Bean
    public Binding seckillRetryBinding2() {
        return BindingBuilder.bind(seckillRetryQueue2())
                .to(seckillDlxExchange())
                .with("seckill.retry.2");
    }

    @Bean
    public Queue seckillRetryQueue3() {
        return QueueBuilder.durable("seckill.retry.queue.3")
                .deadLetterExchange("seckill.topic")
                .deadLetterRoutingKey("seckill.success")
                .ttl(120_000) // 120 秒延迟
                .build();
    }

    @Bean
    public Binding seckillRetryBinding3() {
        return BindingBuilder.bind(seckillRetryQueue3())
                .to(seckillDlxExchange())
                .with("seckill.retry.3");
    }

    /**
     * 工作队列（替代原来在 @RabbitListener 中声明的队列）
     * 需要手动创建以添加 DLX 配置
     */
    @Bean
    public Queue seckillWorkQueue() {
        if (deadLetterEnabled) {
            return QueueBuilder.durable("seckill.queue")
                    .deadLetterExchange("seckill.dlx")  // 失败消息路由到 DLX
                    .deadLetterRoutingKey("seckill.retry.1")
                    .build();
        } else {
            return QueueBuilder.durable("seckill.queue").build();
        }
    }

    @Bean
    public Binding seckillWorkBinding() {
        return BindingBuilder.bind(seckillWorkQueue())
                .to(seckillExchange())
                .with("seckill.success");
    }
}
