package com.prism.chat.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/**
 * chat 服务基础装配。
 * Embedding 客户端切换见 EmbeddingConfig；MQ 发布双实现见 MqConfig。
 */
@Configuration
@EnableConfigurationProperties(ChatProperties.class)
public class ChatConfig {

    @Bean
    public ReactiveStringRedisTemplate reactiveStringRedisTemplate(ReactiveRedisConnectionFactory factory) {
        return new ReactiveStringRedisTemplate(factory);
    }
}
