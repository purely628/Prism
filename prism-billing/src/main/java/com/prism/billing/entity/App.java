package com.prism.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 接入应用（t_app）——billing 侧只读：
 * 用量 topApps 取应用名、quota 接口取月配额时直接读表。
 * 演示环境 user/billing/kb 三服务共库，故本服务直接查 t_app；
 * 生产环境应拆库，改为服务间调用（user 服务提供内部接口）。
 */
@Data
@TableName("t_app")
public class App {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String appName;

    private String appKey;

    private Long monthlyQuota;

    private Integer rateLimit;

    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
