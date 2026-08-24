package com.llsp.config;

import com.llsp.mapper.OutboxMessageMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.lang.reflect.Field;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class MqConfigTest {

    @Test
    void confirmCallback_ackTrue_marksOutboxAsSent() throws Exception {
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
        OutboxMessageMapper mapper = mock(OutboxMessageMapper.class);
        MqConfig config = new MqConfig();

        RabbitTemplate template = config.rabbitTemplate(connectionFactory, mapper);
        RabbitTemplate.ConfirmCallback callback = getConfirmCallback(template);

        callback.confirm(new CorrelationData("msg-123"), true, null);

        verify(mapper).markAsSentByMessageId("msg-123");
    }

    @Test
    void confirmCallback_ackFalse_keepsPending() throws Exception {
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
        OutboxMessageMapper mapper = mock(OutboxMessageMapper.class);
        MqConfig config = new MqConfig();

        RabbitTemplate template = config.rabbitTemplate(connectionFactory, mapper);
        RabbitTemplate.ConfirmCallback callback = getConfirmCallback(template);

        callback.confirm(new CorrelationData("msg-123"), false, "exchange not found");

        verify(mapper, never()).markAsSentByMessageId(anyString());
    }

    /**
     * RabbitTemplate 未暴露 getConfirmCallback() 公开 getter，
     * 这里通过反射读取 private 的 confirmCallback 字段以触发确认回调
     */
    private RabbitTemplate.ConfirmCallback getConfirmCallback(RabbitTemplate template) throws Exception {
        for (Field field : RabbitTemplate.class.getDeclaredFields()) {
            if (field.getType() == RabbitTemplate.ConfirmCallback.class) {
                field.setAccessible(true);
                return (RabbitTemplate.ConfirmCallback) field.get(template);
            }
        }
        throw new IllegalStateException("未找到 RabbitTemplate.confirmCallback 字段");
    }
}
