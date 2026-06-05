package com.llsp.controller;

import com.llsp.dto.Result;
import com.llsp.repository.ChatHistoryRepository;
import com.llsp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
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
    @PostMapping(value = "/chat/{chatId}/send", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@PathVariable String chatId, 
                             @RequestParam String prompt) {
        Long userId = UserHolder.getUser().getId();
        // 1. 先验证会话归属
        List<String> chatIds = chatHistoryRepository.getChatIds(userId);
        if (!chatIds.contains(chatId)) {
            log.warn("用户 {} 尝试访问不属于自己的会话 {}", userId, chatId);
            return Flux.just("会话不存在或无权限");
        }
        chatHistoryRepository.save(userId, chatId);
        return serviceChatClient.prompt()
                .user(prompt)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, chatId))
                .stream()
                .content()
                .timeout(Duration.ofSeconds(5), Flux.defer(() -> {
                    log.warn("AI 首字响应超时 (5s)，会话ID: {}", chatId);
                    return Flux.just("AI 响应超时，请稍后重试");
                }))
                .onErrorResume(e -> {
                    log.error("AI 对话异常，会话ID: {}, 错误: {}", chatId, e.getMessage());
                    return Flux.just("AI 服务异常");
                })
                .defaultIfEmpty("未收到AI响应");
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