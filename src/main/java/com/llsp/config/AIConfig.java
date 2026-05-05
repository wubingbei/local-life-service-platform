package com.llsp.config;

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
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class AIConfig {
    
    @Bean
    public ChatMemory chatMemory(StringRedisTemplate stringRedisTemplate) {
        // 使用基于Redis的ChatMemory，保留最近20条消息
        return new RedisChatMemory(stringRedisTemplate, 20);
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
