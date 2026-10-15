package com.prism.common.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 一次推理调用的用量事件：chat 服务在流结束后发出，
 * billing 服务异步消费入库并累加 Redis 配额计数。
 * msgId 全局唯一（雪花 ID），消费端以此为幂等键。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsageEvent implements Serializable {

    private String msgId;
    private Long appId;
    private String appKey;
    private String model;
    private Long channelId;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Long latencyMs;
    /** 是否命中语义缓存（命中则本条计费标记 cacheHit，用于看板统计） */
    private Boolean cacheHit;
    /** yyyy-MM-dd HH:mm:ss，事件产生时间 */
    private String occurredAt;
}
