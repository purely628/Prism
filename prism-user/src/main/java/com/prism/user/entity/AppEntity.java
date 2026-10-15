package com.prism.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 接入应用 t_app */
@Data
@TableName("t_app")
public class AppEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String appName;

    /** sk-prism- + 32 位 hex */
    private String appKey;

    /** 月 token 配额 */
    private Long monthlyQuota;

    /** 每秒允许请求数（网关令牌桶速率） */
    private Integer rateLimit;

    /** 1 启用 0 禁用 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
