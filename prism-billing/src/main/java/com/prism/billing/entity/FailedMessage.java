package com.prism.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消费失败落库（t_failed_message）：重试 3 次仍失败的消息（Rabbit DLQ / Stream 毒消息）兜底。
 */
@Data
@TableName("t_failed_message")
public class FailedMessage {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String msgId;

    /** 原始 JSON */
    private String payload;

    private String error;

    private LocalDateTime createdAt;
}
