package com.llsp.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RedisChatMemory implements ChatMemory {
    
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    
    private static final String CHAT_MEMORY_KEY = "chat:memory:";
    private static final long TTL_DAYS = 7;
    private final int maxMessages;

    public RedisChatMemory(StringRedisTemplate stringRedisTemplate, int maxMessages) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.maxMessages = maxMessages;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        String key = CHAT_MEMORY_KEY + conversationId;
        
        // 获取现有的消息列表
        List<String> existingMessages = stringRedisTemplate.opsForList().range(key, 0, -1);
        List<String> allMessages = existingMessages != null ? new ArrayList<>(existingMessages) : new ArrayList<>();
        
        // 添加新消息
        for (Message message : messages) {
            allMessages.add(serializeMessage(message));
        }
        
        // 只保留最新的maxMessages条消息
        if (allMessages.size() > maxMessages) {
            allMessages = allMessages.subList(allMessages.size() - maxMessages, allMessages.size());
        }
        
        // 清空旧数据并写入新数据
        stringRedisTemplate.delete(key);
        for (String msg : allMessages) {
            stringRedisTemplate.opsForList().rightPush(key, msg);
        }
        
        // 设置过期时间
        stringRedisTemplate.expire(key, TTL_DAYS, TimeUnit.DAYS);
    }

    @Override
    public List<Message> get(String conversationId) {
        String key = CHAT_MEMORY_KEY + conversationId;
        List<String> messages = stringRedisTemplate.opsForList().range(key, 0, -1);
        
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        
        return messages.stream()
                .map(this::deserializeMessage)
                .toList();
    }

    @Override
    public void clear(String conversationId) {
        String key = CHAT_MEMORY_KEY + conversationId;
        stringRedisTemplate.delete(key);
    }

    /**
     * 序列化消息为简单的 JSON (只存角色和文本)
     */
    private String serializeMessage(Message message) {
        Map<String, String> map = new HashMap<>();
        String role;
        if (message instanceof UserMessage) {
            role = "user";
        } else if (message instanceof AssistantMessage) {
            role = "assistant";
        } else {
            role = message.getMessageType().name().toLowerCase();
        }
        map.put("role", role);
        map.put("text", message.getText());
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化消息失败", e);
        }
    }

    /**
     * 反序列化 JSON 为具体的 Message 对象
     */
    private Message deserializeMessage(String json) {
        try {
            Map<String, String> map = objectMapper.readValue(json, Map.class);
            String role = map.get("role");
            String text = map.get("text");
            
            if (text == null) text = "";

            if (role != null && (role.equalsIgnoreCase("user") || role.equalsIgnoreCase("human"))) {
                return new UserMessage(text);
            } else {
                return new AssistantMessage(text);
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException("反序列化消息失败", e);
        }
    }
}
