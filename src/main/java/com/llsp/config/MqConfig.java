package com.llsp.config;

import com.llsp.mapper.OutboxMessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class MqConfig {

    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 配置 RabbitTemplate，开启 Publisher Confirm 和 Return Callback
     * 配合 Outbox 模式使用：Confirm 失败时由定时补发兜底
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         OutboxMessageMapper outboxMessageMapper) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter());

        // 开启 Publisher Confirm（异步确认消息是否到达交换机）
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (ack) {
                // 到达交换机，立即把 Outbox 标记为已发送，避免定时任务重复投递
                if (correlationData != null && correlationData.getId() != null) {
                    try {
                        outboxMessageMapper.markAsSentByMessageId(correlationData.getId());
                    } catch (Exception e) {
                        log.error("标记 Outbox 已发送失败 - messageId={}", correlationData.getId(), e);
                    }
                }
                log.debug("消息已到达交换机 - correlationId={}",
                        correlationData != null ? correlationData.getId() : "null");
            } else {
                log.warn("消息未能到达交换机 - correlationId={}, cause={}",
                        correlationData != null ? correlationData.getId() : "null", cause);
                // Confirm 失败保持 PENDING，由 Outbox 定时任务兜底重发
            }
        });

        // 开启 Return Callback（消息从交换机路由到队列失败时回调）
        template.setReturnsCallback(returned -> {
            log.warn("消息路由失败 - exchange={}, routingKey={}, replyCode={}, replyText={}",
                    returned.getExchange(), returned.getRoutingKey(),
                    returned.getReplyCode(), returned.getReplyText());
        });
        template.setMandatory(true); // 必须开启 mandatory 才能触发 ReturnCallback

        return template;
    }
}
