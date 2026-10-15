package com.prism.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 月度账单（t_bill）：每月 1 号 03:00 定时 / 手动接口出账，uk_app_month UPSERT。
 */
@Data
@TableName("t_bill")
public class Bill {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long appId;

    /** yyyy-MM */
    private String billMonth;

    private Long totalTokens;

    private Long callCount;

    /** 模拟单价计费（元）：prompt 0.002/1k，completion 0.004/1k */
    private BigDecimal amount;

    /** 0 待确认 1 已出账 */
    private Integer status;

    private LocalDateTime createdAt;
}
