package com.prism.billing.consumer;

/**
 * 用量消费入口（契约 §5 双实现约定）：
 * - RedisStreamUsageConsumer：默认（prism.mq.mode=stream 或缺省）
 * - RabbitUsageConsumer：prism.mq.mode=rabbit（compose 环境）
 */
public interface UsageConsumer {
}
