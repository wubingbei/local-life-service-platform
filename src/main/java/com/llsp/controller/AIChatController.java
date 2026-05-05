package com.llsp.controller;

import com.llsp.dto.Result;
import com.llsp.repository.ChatHistoryRepository;
import com.llsp.utils.SystemConstants;
import com.llsp.utils.UserHolder;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/ai")
public class AIChatController {

    @Resource
    private ChatClient serviceChatClient;
    
    @Resource
    private ChatHistoryRepository chatHistoryRepository;
    
    @Resource
    private ChatMemory chatMemory;

    /**
     * 创建新会话
     */
    @PostMapping("/chat/new")
    public Result createChat() {
        Long userId = UserHolder.getUser().getId();
        String chatId = chatHistoryRepository.generateChatId();
        chatHistoryRepository.save(userId, chatId);
        return Result.ok(chatId);
    }

    /**
     * 发送消息（需要指定会话ID）
     */
    @PostMapping("/chat/{chatId}/send")
    public Result sendMessage(@PathVariable String chatId, 
                              @RequestParam String message) {
        Long userId = UserHolder.getUser().getId();
        // 验证会话ID是否属于当前用户
        List<String> chatIds = chatHistoryRepository.getChatIds(userId);
        if (!chatIds.contains(chatId)) {
            return Result.fail("会话不存在");
        }
        
        // 调用AI服务，注意：参数名必须使用 ChatMemory.CHAT_MEMORY_CONVERSATION_ID_KEY
        String response = serviceChatClient.prompt()
                .system(SystemConstants.SERVICE_SYSTEM_PROMPT)
                .user(message)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .content();
        
        return Result.ok(response);
    }

    /**
     * 获取用户的历史会话列表
     */
    @GetMapping("/chat/history")
    public Result getChatHistory() {
        Long userId = UserHolder.getUser().getId();
        List<String> chatIds = chatHistoryRepository.getChatIds(userId);
        return Result.ok(chatIds);
    }
    
    /**
     * 获取指定会话的消息历史
     */
    @GetMapping("/chat/{chatId}/messages")
    public Result getChatMessages(@PathVariable String chatId) {
        Long userId = UserHolder.getUser().getId();
        // 验证会话ID是否属于当前用户
        List<String> chatIds = chatHistoryRepository.getChatIds(userId);
        if (!chatIds.contains(chatId)) {
            return Result.fail("会话不存在");
        }
        
        // 从ChatMemory中获取消息列表
        List<Message> messages = chatMemory.get(chatId);
        
        // 转换为前端友好的格式
        List<Map<String, String>> messageList = messages.stream()
                .map(msg -> {
                    String role = msg.getMessageType().getValue().toLowerCase();
                    return Map.of(
                            "role", role,
                            "content", msg.getText()
                    );
                })
                .collect(Collectors.toList());
        
        return Result.ok(messageList);
    }
    
    /**
     * 删除指定会话
     */
    @DeleteMapping("/chat/{chatId}")
    public Result deleteChat(@PathVariable String chatId) {
        Long userId = UserHolder.getUser().getId();
        List<String> chatIds = chatHistoryRepository.getChatIds(userId);
        if (!chatIds.contains(chatId)) {
            return Result.fail("会话不存在");
        }

        chatHistoryRepository.delete(userId, chatId);
        // 同步清理 ChatMemory 中的消息记录
        chatMemory.clear(chatId);

        return Result.ok("删除成功");
    }
}
