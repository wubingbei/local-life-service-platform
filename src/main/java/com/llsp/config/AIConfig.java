package com.llsp.config;

import com.llsp.mapper.ChatMessageMapper;
import com.llsp.mapper.ChatSessionMapper;
import com.llsp.tools.BlogTools;
import com.llsp.tools.SeckillTools;
import com.llsp.tools.ShopTools;
import com.llsp.tools.VoucherTools;
import com.llsp.utils.SystemConstants;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AIConfig {

    @Bean
    public ChatMemory chatMemory(ChatMessageMapper messageMapper,
                                  ChatSessionMapper sessionMapper) {
        // 使用基于MySQL的ChatMemory，保留最近50条消息（不再受Redis内存限制）
        return new MysqlChatMemory(messageMapper, sessionMapper, 50);
    }

    @Bean
    public ChatClient serviceChatClient(ChatModel chatModel, ChatMemory chatMemory, 
                                        ShopTools shopTools, VoucherTools voucherTools,
                                        SeckillTools seckillTools, BlogTools blogTools) {
        return ChatClient
                .builder(chatModel)
                .defaultSystem(SystemConstants.SERVICE_SYSTEM_PROMPT)
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
                )
                .defaultTools(shopTools, voucherTools, seckillTools, blogTools)
                .build();
    }
}
