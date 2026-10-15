package com.prism.chat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.chat.mq.RabbitUsagePublisher;
import com.prism.chat.mq.RedisStreamUsagePublisher;
import com.prism.chat.mq.UsageEventPublisher;
import com.prism.common.constant.MqConstants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/**
 * 用量事件发布装配（契约 §5 双实现约定）：
 * 默认 stream（Redis Stream XADD，MAXLEN 10000）；prism.mq.mode=rabbit 时切 RabbitMQ。
 */
@Configuration
public class MqConfig {

    /** Rabbit 拓扑：usage 直连交换机 + 消费队列 + 绑定 + DLQ（与 billing 消费端约定一致） */
    @Bean
    @ConditionalOnProperty(name = "prism.mq.mode", havingValue = "rabbit")
    public Declarables usageTopology() {
        DirectExchange exchange = new DirectExchange(MqConstants.USAGE_EXCHANGE, true, false);
        Queue queue = QueueBuilder.durable(MqConstants.USAGE_QUEUE)
                .deadLetterExchange(MqConstants.USAGE_EXCHANGE)
                .deadLetterRoutingKey(MqConstants.USAGE_DLQ_ROUTING_KEY)
                .build();
        Queue dlq = QueueBuilder.durable(MqConstants.USAGE_DLQ).build();
        Binding binding = BindingBuilder.bind(queue).to(exchange).with(MqConstants.USAGE_ROUTING_KEY);
        Binding dlqBinding = BindingBuilder.bind(dlq).to(exchange).with(MqConstants.USAGE_DLQ_ROUTING_KEY);
        return new Declarables(exchange, queue, dlq, binding, dlqBinding);
    }

    /** Rabbit 消息体用 JSON（跨服务消费端按同名字段反序列化） */
    @Bean
    @ConditionalOnProperty(name = "prism.mq.mode", havingValue = "rabbit")
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    @ConditionalOnProperty(name = "prism.mq.mode", havingValue = "rabbit")
    public UsageEventPublisher rabbitUsagePublisher(RabbitTemplate rabbitTemplate) {
        return new RabbitUsagePublisher(rabbitTemplate);
    }

    /** 默认实现：Redis Stream（local 环境零外部 MQ 依赖） */
    @Bean
    @ConditionalOnProperty(name = "prism.mq.mode", havingValue = "stream", matchIfMissing = true)
    public UsageEventPublisher redisStreamUsagePublisher(ReactiveStringRedisTemplate redis, ObjectMapper objectMapper) {
        return new RedisStreamUsagePublisher(redis, objectMapper);
    }
}
