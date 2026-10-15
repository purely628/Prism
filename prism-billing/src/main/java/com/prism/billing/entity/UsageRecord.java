package com.prism.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 推理用量流水（t_usage_record）：billing 消费 MQ 后攒批写入。
 * 主键雪花 ID 由消费端生成（IdType.INPUT）。
 */
@Data
@TableName("t_usage_record")
public class UsageRecord {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 消息幂等键（uk_msg_id 唯一索引——重复投递的最后防线） */
    private String msgId;

    private Long appId;

    private String model;

    private Long channelId;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private Long latencyMs;

    /** 是否命中语义缓存 */
    private Boolean cacheHit;

    /** 流水时间（事件发生时间，非入库时间） */
    private LocalDateTime createdAt;
}
