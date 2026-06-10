package com.llsp.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.llsp.entity.ChatMessage;
import com.llsp.entity.ChatSession;
import com.llsp.mapper.ChatMessageMapper;
import com.llsp.mapper.ChatSessionMapper;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * ChatMemory 的 MySQL 实现，替代 RedisChatMemory
 * <p>
 * 消息持久化到 tb_chat_message 表，通过 user_id 实现数据隔离
 */
public class MysqlChatMemory implements ChatMemory {

    private final ChatMessageMapper messageMapper;
    private final ChatSessionMapper sessionMapper;
    private final int maxMessages;

    public MysqlChatMemory(ChatMessageMapper messageMapper,
                           ChatSessionMapper sessionMapper,
                           int maxMessages) {
        this.messageMapper = messageMapper;
        this.sessionMapper = sessionMapper;
        this.maxMessages = maxMessages;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        Long userId = getUserIdByChatId(conversationId);

        // 批量插入新消息
        List<ChatMessage> entities = new ArrayList<>();
        for (Message message : messages) {
            ChatMessage entity = new ChatMessage();
            entity.setChatId(conversationId);
            entity.setUserId(userId);
            entity.setRole(toRoleString(message));
            entity.setContent(message.getText());
            entities.add(entity);
        }
        for (ChatMessage entity : entities) {
            messageMapper.insert(entity);
        }

        updateSessionMeta(conversationId, userId);
    }

    @Override
    public List<Message> get(String conversationId) {
        Long userId = getUserIdByChatId(conversationId);

        List<ChatMessage> entities = messageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getChatId, conversationId)
                        .eq(ChatMessage::getUserId, userId)
                        .orderByAsc(ChatMessage::getId)
        );
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        // 只返回最近 maxMessages 条
        if (entities.size() > maxMessages) {
            entities = entities.subList(entities.size() - maxMessages, entities.size());
        }
        return entities.stream()
                .map(this::toMessage)
                .toList();
    }

    @Override
    public void clear(String conversationId) {
        Long userId = getUserIdByChatId(conversationId);

        // 删除该会话下该用户的所有消息
        messageMapper.delete(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getChatId, conversationId)
                .eq(ChatMessage::getUserId, userId));
    }

    /**
     * 更新会话的 message_count，如果标题为空则用首条用户消息填充
     */
    private void updateSessionMeta(String chatId, Long userId) {
        ChatSession session = sessionMapper.selectOne(
                new LambdaQueryWrapper<ChatSession>()
                        .eq(ChatSession::getChatId, chatId)
                        .eq(ChatSession::getUserId, userId)
        );
        if (session == null) {
            return;
        }

        // 统计消息数
        Long count = messageMapper.selectCount(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getChatId, chatId)
                        .eq(ChatMessage::getUserId, userId)
        );

        LambdaUpdateWrapper<ChatSession> updateWrapper = new LambdaUpdateWrapper<ChatSession>()
                .eq(ChatSession::getChatId, chatId)
                .set(ChatSession::getMessageCount, count != null ? count.intValue() : 0);

        // 如果还没有标题，取首条用户消息的前 30 字
        if (session.getTitle() == null || session.getTitle().isEmpty()) {
            List<ChatMessage> userMessages = messageMapper.selectList(
                    new LambdaQueryWrapper<ChatMessage>()
                            .eq(ChatMessage::getChatId, chatId)
                            .eq(ChatMessage::getUserId, userId)
                            .eq(ChatMessage::getRole, "user")
                            .orderByAsc(ChatMessage::getId)
                            .last("LIMIT 1")
            );
            if (userMessages != null && !userMessages.isEmpty()) {
                String content = userMessages.get(0).getContent();
                String title = content != null && content.length() > 30
                        ? content.substring(0, 30)
                        : content;
                updateWrapper.set(ChatSession::getTitle, title);
            }
        }

        sessionMapper.update(null, updateWrapper);
    }

    private String toRoleString(Message message) {
        if (message instanceof UserMessage) {
            return "user";
        } else if (message instanceof AssistantMessage) {
            return "assistant";
        }
        return message.getMessageType().name().toLowerCase();
    }

    /**
     * 暴露消息上限，供 Controller 做前置检查
     */
    public int getMaxMessages() {
        return maxMessages;
    }

    /**
     * 通过 chat_id 从会话表反查 userId，避免依赖 ThreadLocal（Reactor 线程上 UserHolder 为 null）
     */
    private Long getUserIdByChatId(String chatId) {
        ChatSession session = sessionMapper.selectOne(
                new LambdaQueryWrapper<ChatSession>()
                        .eq(ChatSession::getChatId, chatId)
                        .select(ChatSession::getUserId)
        );
        if (session == null) {
            throw new RuntimeException("会话不存在: " + chatId);
        }
        return session.getUserId();
    }

    private Message toMessage(ChatMessage entity) {
        String text = entity.getContent();
        if (text == null) text = "";

        if ("user".equalsIgnoreCase(entity.getRole())) {
            return new UserMessage(text);
        } else {
            return new AssistantMessage(text);
        }
    }
}
