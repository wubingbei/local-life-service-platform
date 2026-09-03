package com.llsp.listeners;

import com.llsp.entity.VoucherOrder;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SeckillListenerTest {

    /**
     * tryLock 失败时应抛异常进重试队列，而非静默 return 导致丢单（消息已 ACK）
     */
    @Test
    void handleVoucherOrder_throwsWhenLockUnavailable() {
        SeckillListener listener = new SeckillListener();
        RedissonClient redissonClient = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        ReflectionTestUtils.setField(listener, "redissonClient", redissonClient);
        when(redissonClient.getLock(anyString())).thenReturn(lock);

        try {
            when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(false);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        VoucherOrder order = new VoucherOrder();
        order.setUserId(1L);
        order.setVoucherId(1L);

        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(listener, "handleVoucherOrder", order));
    }

    /**
     * P1-3：毒消息（无法反序列化）应在 reject 前先入死信队列留痕，
     * 不能因队列级 DLX 已移除而凭空消失。
     */
    @Test
    void listenSeckillOrder_sendsPoisonMessageToDeadLetter() {
        SeckillListener listener = new SeckillListener();
        Jackson2JsonMessageConverter messageConverter = mock(Jackson2JsonMessageConverter.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        ReflectionTestUtils.setField(listener, "messageConverter", messageConverter);
        ReflectionTestUtils.setField(listener, "rabbitTemplate", rabbitTemplate);
        ReflectionTestUtils.setField(listener, "deadLetterEnabled", true);

        Message poison = new Message("not a valid json".getBytes());
        when(messageConverter.fromMessage(poison)).thenThrow(new RuntimeException("解析失败"));

        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> listener.listenSeckillOrder(poison));

        // 关键断言：reject 前已把原始消息投递到死信交换机
        verify(rabbitTemplate).send(eq("seckill.dlx"), eq("seckill.dlx"), eq(poison));
    }
}
