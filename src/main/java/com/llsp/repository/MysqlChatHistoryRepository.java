package com.llsp.repository;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.llsp.dto.ChatSessionDTO;
import com.llsp.entity.ChatMessage;
import com.llsp.entity.ChatSession;
import com.llsp.mapper.ChatMessageMapper;
import com.llsp.mapper.ChatSessionMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * ChatHistoryRepository 的 MySQL 实现，替代 InMemoryChatHistoryRepository
 * <p>
 * 会话元数据持久化到 tb_chat_session 表，通过 user_id 实现数据隔离
 */
@Component
public class MysqlChatHistoryRepository implements ChatHistoryRepository {

    @Resource
    private ChatSessionMapper sessionMapper;

    @Resource
    private ChatMessageMapper messageMapper;

    @Override
    public void save(Long userId, String chatId) {
        // 检查会话是否已存在，不存在则插入
        ChatSession existing = sessionMapper.selectOne(
                new LambdaQueryWrapper<ChatSession>()
                        .eq(ChatSession::getChatId, chatId)
        );
        if (existing == null) {
            ChatSession session = new ChatSession();
            session.setUserId(userId);
            session.setChatId(chatId);
            sessionMapper.insert(session);
        }
        // 如果已存在，更新 update_time 使排序靠前
        // MyBatis-Plus 的 update 在 update_time 上有 ON UPDATE CURRENT_TIMESTAMP，但这里需要手动触发
        // 通过 update 一个无关字段来刷新 update_time，或者直接依赖 MySQL 的 ON UPDATE
        // 这里简单处理：如果会话已存在且属于当前用户，更新 update_time
        if (existing != null && existing.getUserId().equals(userId)) {
            ChatSession update = new ChatSession();
            update.setId(existing.getId());
            update.setMessageCount(existing.getMessageCount()); // 保持原值，只为触发 update_time 更新
            sessionMapper.updateById(update);
        }
    }

    /**
     * 获取指定用户的所有聊天会话ID列表
     * <p>
     * 根据用户ID查询该用户的所有聊天会话，并按更新时间降序排列，
     * @param userId 用户ID，用于筛选该用户的聊天会话
     * @return 聊天会话ID列表，按更新时间降序排列；如果没有会话则返回空列表
     */
    @Override
    public List<String> getChatIds(Long userId) {
        // 查询指定用户的所有会话，按更新时间降序排列
        List<ChatSession> sessions = sessionMapper.selectList(
                new LambdaQueryWrapper<ChatSession>()
                        .eq(ChatSession::getUserId, userId)
                        .orderByDesc(ChatSession::getUpdateTime)
        );
        if (sessions == null || sessions.isEmpty()) {
            return List.of();
        }
        // 提取会话ID并返回列表
        return sessions.stream()
                .map(ChatSession::getChatId)
                .toList();
    }

    @Override
    public List<ChatSessionDTO> getSessionList(Long userId) {
        List<ChatSession> sessions = sessionMapper.selectList(
                new LambdaQueryWrapper<ChatSession>()
                        .eq(ChatSession::getUserId, userId)
                        .orderByDesc(ChatSession::getUpdateTime)
        );
        if (sessions == null || sessions.isEmpty()) {
            return List.of();
        }
        return sessions.stream()
                .map(s -> new ChatSessionDTO(
                        s.getChatId(),
                        s.getTitle() != null && !s.getTitle().isEmpty()
                                ? s.getTitle()
                                : "新会话"))
                .toList();
    }

    @Override
    public String generateChatId() {
        return IdUtil.fastSimpleUUID();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, String chatId) {
        // 先删消息，再删会话
        messageMapper.delete(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getChatId, chatId)
                .eq(ChatMessage::getUserId, userId));
        sessionMapper.delete(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getChatId, chatId)
                .eq(ChatSession::getUserId, userId));
    }
}
