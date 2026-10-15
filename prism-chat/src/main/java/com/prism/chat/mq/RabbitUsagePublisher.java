package com.prism.chat.mq;

import com.prism.common.constant.MqConstants;
import com.prism.common.mq.UsageEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * RabbitMQ 实现（prism.mq.mode=rabbit 时由 MqConfig 装配，拓扑 Declarables 同处声明）。
 * RabbitTemplate 是阻塞客户端（AMQP 同步发送），必须隔离到 boundedElastic，
 * 避免占用 Netty 事件循环线程。
 */
@Slf4j
@RequiredArgsConstructor
public class RabbitUsagePublisher implements UsageEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    @Override
    public Mono<Void> publish(UsageEvent event) {
        return Mono.fromRunnable(() ->
                        rabbitTemplate.convertAndSend(MqConstants.USAGE_EXCHANGE, MqConstants.USAGE_ROUTING_KEY, event))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
}
