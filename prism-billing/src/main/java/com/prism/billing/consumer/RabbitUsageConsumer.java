package com.prism.billing.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.billing.ingest.UsageIngestService;
import com.prism.billing.service.FailedMessageService;
import com.prism.common.constant.MqConstants;
import com.prism.common.mq.UsageEvent;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Rabbit 用量消费者（prism.mq.mode=rabbit）：
 * 手动 ack；同一消息就地重试 3 次（间隔 1s），仍失败 basicNack(requeue=false)
 * 经队列死信参数路由到 DLQ；DLQ 监听把消息原文落 t_failed_message 后 ack。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "prism.mq.mode", havingValue = "rabbit")
public class RabbitUsageConsumer implements UsageConsumer {

    private final UsageIngestService ingestService;
    private final FailedMessageService failedMessageService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = MqConstants.USAGE_QUEUE, ackMode = "MANUAL")
    public void onUsage(String body, Channel channel,
                        @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                UsageEvent event = objectMapper.readValue(body, UsageEvent.class);
                ingestService.ingest(event, null);
                channel.basicAck(deliveryTag, false);
                return;
            } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
                lastError = (e instanceof RuntimeException re) ? re : new RuntimeException(e);
                log.warn("usage 消费第 {}/3 次失败: {}", attempt, e.getMessage());
                sleepQuietly(1000);
            }
        }
        // 3 次耗尽 → 死信（DLQ 监听落 t_failed_message）
        log.error("usage 消费重试耗尽，转入 DLQ: {}", lastError.getMessage());
        channel.basicNack(deliveryTag, false, false);
    }

    @RabbitListener(queues = MqConstants.USAGE_DLQ, ackMode = "MANUAL")
    public void onDeadLetter(String body, Channel channel,
                             @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            failedMessageService.save(body, "retry-exhausted(3)");
        } finally {
            channel.basicAck(deliveryTag, false);
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
