package com.prism.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 对账差异记录（t_reconcile_diff）：MySQL 流水汇总 vs Redis 计数器不一致时落一条，并把 Redis 修正为 DB 值。
 */
@Data
@TableName("t_reconcile_diff")
public class ReconcileDiff {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private LocalDate reconcileDay;

    private Long appId;

    private Long dbTokens;

    private Long redisTokens;

    private Long diff;

    private LocalDateTime createdAt;
}
