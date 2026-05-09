package com.llsp.repository;

import cn.hutool.core.util.IdUtil;
import com.llsp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class InMemoryChatHistoryRepository implements ChatHistoryRepository{
    
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void save(Long userId, String chatId) {
        String key = RedisConstants.CHAT_HISTORY_KEY + userId;
        // 检查chatId是否已存在
        List<String> existingChats = stringRedisTemplate.opsForList().range(key, 0, -1);
        if (existingChats == null || !existingChats.contains(chatId)) {
            // 只有不存在时才添加
            stringRedisTemplate.opsForList().rightPush(key, chatId);
        }
        // 设置过期时间（7天）
        stringRedisTemplate.expire(key, RedisConstants.CHAT_HISTORY_TTL, TimeUnit.DAYS);
    }

    @Override
    public List<String> getChatIds(Long userId) {
        String key = RedisConstants.CHAT_HISTORY_KEY + userId;
        // 获取用户的所有会话ID
        List<String> chatIds = stringRedisTemplate.opsForList().range(key, 0, -1);
        return chatIds != null ? chatIds : List.of();
    }
    
    @Override
    public String generateChatId() {
        // 使用UUID生成唯一的会话ID
        return IdUtil.fastSimpleUUID();
    }
    
    @Override
    public void delete(Long userId, String chatId) {
        String historyKey = RedisConstants.CHAT_HISTORY_KEY + userId;
        String memoryKey = RedisConstants.CHAT_MEMORY_KEY + chatId;
        
        // 从会话列表中删除chatId
        stringRedisTemplate.opsForList().remove(historyKey, 1, chatId);
        
        // 删除该会话的所有消息记录
        stringRedisTemplate.delete(memoryKey);
    }
}
