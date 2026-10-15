package com.prism.billing.consumer;

import com.prism.common.constant.MqConstants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Rabbit 拓扑（契约 §5）：
 * direct exchange prism.usage.direct + queue prism.usage.record + DLQ；
 * 主队列配置 x-dead-letter-exchange/routing-key，nack(requeue=false) 自动路由到 DLQ。
 */
@Configuration
@ConditionalOnProperty(name = "prism.mq.mode", havingValue = "rabbit")
public class RabbitUsageTopology {

    @Bean
    public Declarables usageTopology() {
        DirectExchange exchange = new DirectExchange(MqConstants.USAGE_EXCHANGE, true, false);
        Queue mainQueue = QueueBuilder.durable(MqConstants.USAGE_QUEUE)
                .deadLetterExchange(MqConstants.USAGE_EXCHANGE)
                .deadLetterRoutingKey(MqConstants.USAGE_DLQ_ROUTING_KEY)
                .build();
        Queue dlqQueue = QueueBuilder.durable(MqConstants.USAGE_DLQ).build();
        Binding mainBinding = BindingBuilder.bind(mainQueue).to(exchange).with(MqConstants.USAGE_ROUTING_KEY);
        Binding dlqBinding = BindingBuilder.bind(dlqQueue).to(exchange).with(MqConstants.USAGE_DLQ_ROUTING_KEY);
        return new Declarables(exchange, mainQueue, dlqQueue, mainBinding, dlqBinding);
    }
}
