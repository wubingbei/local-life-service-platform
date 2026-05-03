package com.llsp.config;

import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {
    
    @Value("${spring.redisson.address:}")
    private String address;
    
    @Value("${spring.redisson.password:}")
    private String password;
    
    @Bean
    public RedissonClient redissonClient(){
        Config config = new org.redisson.config.Config();
        config.useSingleServer()
              .setAddress(address)
              .setPassword(password.isEmpty() ? null : password);
        return org.redisson.Redisson.create(config);
    }
}
