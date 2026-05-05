package com.llsp.repository;

import java.util.List;

public interface ChatHistoryRepository {
    /**
     * 保存会话记录
     * @param userId 用户ID
     * @param chatId 会话ID
     */
    void save(Long userId, String chatId);

    /**
     * 获取用户的会话ID列表
     * @param userId 用户ID
     * @return 会话ID列表
     */
    List<String> getChatIds(Long userId);
    
    /**
     * 生成新的会话ID
     * @return 会话ID
     */
    String generateChatId();
    
    /**
     * 删除指定会话
     * @param userId 用户ID
     * @param chatId 会话ID
     */
    void delete(Long userId, String chatId);
}
