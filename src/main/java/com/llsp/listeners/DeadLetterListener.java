package com.llsp.listeners;

import com.llsp.entity.VoucherOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 死信队列监听器
 * 消费最终失败的消息，记录告警日志，便于人工介入处理
 */
@Slf4j
@Component
public class DeadLetterListener {

    @Resource
    private Jackson2JsonMessageConverter messageConverter;

    /**
     * 监听死信队列，记录告警
     */
    @RabbitListener(queues = "seckill.dlx.queue")
    public void listenDeadLetter(Message message) {
        try {
            VoucherOrder order = (VoucherOrder) messageConverter.fromMessage(message);
            log.error("【死信告警】秒杀订单处理彻底失败 - orderId={}, userId={}, voucherId={}, message={}",
                    order.getId(), order.getUserId(), order.getVoucherId(),
                    new String(message.getBody()));
        } catch (Exception e) {
            log.error("【死信告警】无法解析死信消息 - message={}",
                    new String(message.getBody()), e);
        }

        // TODO: 接入告警系统（钉钉/飞书/邮件/短信）
        // alertService.sendAlert("秒杀订单死信告警", message);
    }
}
