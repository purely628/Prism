package com.prism.billing.consumer;

import com.prism.billing.ingest.MessageAcker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * rabbit 模式（或无 Stream 消费者）下的空 Acker：
 * rabbit 消息的 ack 走 RabbitListener 手动 ack，与 Stream XACK 无关。
 */
@Configuration
public class NoopAckerConfig {

    @Bean
    @ConditionalOnMissingBean(MessageAcker.class)
    public MessageAcker noopMessageAcker() {
        return MessageAcker.NOOP;
    }
}
