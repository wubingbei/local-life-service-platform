package com.llsp.service.impl;

import com.llsp.entity.OutboxMessage;
import com.llsp.mapper.OutboxMessageMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboxRelayServiceTest {

    private final OutboxRelayService service = new OutboxRelayService();

    @Test
    void calculateBackoffDelay_growsExponentially() {
        assertEquals(10_000L, service.calculateBackoffDelay(0));
        assertEquals(20_000L, service.calculateBackoffDelay(1));
        assertEquals(40_000L, service.calculateBackoffDelay(2));
        assertEquals(80_000L, service.calculateBackoffDelay(3));
    }

    @Test
    void calculateBackoffDelay_cappedAtMax() {
        assertEquals(600_000L, service.calculateBackoffDelay(10));
        assertEquals(600_000L, service.calculateBackoffDelay(20));
    }

    /**
     * P1-1：补发后无条件递增重试计数（finally 调用 updateRetryInfo）。
     * ack 时由 ConfirmCallback 把 status 置 1 停止扫描；
     * nack 时 retryCount 递增 + 指数退避，到 maxRetries 后收敛为 FAILED。
     * 关键：补发路径不再乐观 markAsSent，但必须 updateRetryInfo，否则 nack 会无限重发。
     */
    @Test
    void relayPendingMessages_incrementsRetryAfterSend() {
        OutboxRelayService relay = new OutboxRelayService();
        OutboxMessageMapper mapper = mock(OutboxMessageMapper.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        ReflectionTestUtils.setField(relay, "outboxMessageMapper", mapper);
        ReflectionTestUtils.setField(relay, "rabbitTemplate", rabbitTemplate);
        ReflectionTestUtils.setField(relay, "outboxEnabled", true);

        OutboxMessage msg = new OutboxMessage();
        msg.setId(1L);
        msg.setMessageId("msg-1");
        msg.setExchange("seckill.topic");
        msg.setRoutingKey("seckill.success");
        msg.setPayload("{\"id\":1,\"userId\":1,\"voucherId\":1}");
        msg.setRetryCount(0);
        msg.setMaxRetries(10);

        when(mapper.selectPendingMessages(any(LocalDateTime.class), anyInt())).thenReturn(List.of(msg));

        relay.relayPendingMessages();

        // 补发确实发送了 MQ
        verify(rabbitTemplate).convertAndSend(eq("seckill.topic"), eq("seckill.success"),
                any(Object.class), any(CorrelationData.class));
        // 关键断言：无论 confirm 结果如何，retryCount 都已递增并推后重试时间
        verify(mapper).updateRetryInfo(eq(1L), any(LocalDateTime.class));
        // 不在补发路径乐观标记已发送（ack 由 ConfirmCallback 负责）
        verify(mapper, never()).markAsSent(anyLong());
        verify(mapper, never()).markAsSentByMessageId(anyString());
    }

    /**
     * P1-1 收敛：retryCount >= maxRetries 时直接 markAsFailed，不再补发也不再 updateRetryInfo
     */
    @Test
    void relayPendingMessages_marksFailedWhenRetryExhausted() {
        OutboxRelayService relay = new OutboxRelayService();
        OutboxMessageMapper mapper = mock(OutboxMessageMapper.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        ReflectionTestUtils.setField(relay, "outboxMessageMapper", mapper);
        ReflectionTestUtils.setField(relay, "rabbitTemplate", rabbitTemplate);
        ReflectionTestUtils.setField(relay, "outboxEnabled", true);

        OutboxMessage msg = new OutboxMessage();
        msg.setId(2L);
        msg.setMessageId("msg-2");
        msg.setExchange("seckill.topic");
        msg.setRoutingKey("seckill.success");
        msg.setPayload("{\"id\":2}");
        msg.setRetryCount(10);
        msg.setMaxRetries(10);

        when(mapper.selectPendingMessages(any(LocalDateTime.class), anyInt())).thenReturn(List.of(msg));

        relay.relayPendingMessages();

        verify(mapper).markAsFailed(eq(2L), anyString());
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(),
                any(Object.class), any(CorrelationData.class));
        verify(mapper, never()).updateRetryInfo(anyLong(), any(LocalDateTime.class));
    }
}
