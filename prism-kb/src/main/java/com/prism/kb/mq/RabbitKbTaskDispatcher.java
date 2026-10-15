package com.prism.kb.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.common.constant.MqConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * compose 模式实现：direct exchange prism.kb.direct → queue prism.kb.task（routing key kb.task）。
 * 消息体与 Stream 实现完全一致，Python 消费端 aio-pika 解析 {"docId","kbId"}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "prism.mq.mode", havingValue = "rabbit")
public class RabbitKbTaskDispatcher implements KbTaskDispatcher {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void dispatch(long kbId, long docId) {
        try {
            String json = objectMapper.createObjectNode()
                    .put("kbId", kbId).put("docId", docId).toString();
            rabbitTemplate.convertAndSend(MqConstants.KB_EXCHANGE, MqConstants.KB_TASK_ROUTING_KEY, json);
            log.info("kb 任务已投递 RabbitMQ docId={} kbId={}", docId, kbId);
        } catch (Exception e) {
            log.error("kb 任务派发失败 docId={} kbId={}", docId, kbId, e);
        }
    }
}
