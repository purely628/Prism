package com.prism.chat.mq;

import com.prism.common.mq.UsageEvent;
import reactor.core.publisher.Mono;

/**
 * 用量事件发布（契约 §5 双实现）：chat 在推理结束后发布，billing 消费入库。
 * 发布失败由调用方吞掉（计量允许最终一致，丢失由对账兜底），实现不做静默降级。
 */
public interface UsageEventPublisher {

    Mono<Void> publish(UsageEvent event);
}
