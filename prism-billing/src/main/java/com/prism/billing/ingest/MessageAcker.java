package com.prism.billing.ingest;

import org.springframework.data.redis.connection.stream.RecordId;

import java.util.List;

/**
 * Stream 消息回执：只有落库 + 配额累加成功后才 XACK（at-least-once）。
 * 默认 Noop（rabbit 模式 / 单测），Redis Stream 消费者注册真实实现。
 */
public interface MessageAcker {

    MessageAcker NOOP = ids -> { };

    void ack(List<RecordId> ids);
}
