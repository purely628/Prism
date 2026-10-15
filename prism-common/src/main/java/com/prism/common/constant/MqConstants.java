package com.prism.common.constant;

/**
 * MQ 拓扑约定（compose 环境使用真实 RabbitMQ；local 环境用进程内队列适配）
 */
public final class MqConstants {

    private MqConstants() {
    }

    public static final String USAGE_EXCHANGE = "prism.usage.direct";
    public static final String USAGE_QUEUE = "prism.usage.record";
    public static final String USAGE_ROUTING_KEY = "usage.record";
    public static final String USAGE_DLQ = "prism.usage.record.dlq";
    public static final String USAGE_DLQ_ROUTING_KEY = "usage.record.dlq";

    public static final String KB_EXCHANGE = "prism.kb.direct";
    public static final String KB_TASK_QUEUE = "prism.kb.task";
    public static final String KB_TASK_ROUTING_KEY = "kb.task";
}
