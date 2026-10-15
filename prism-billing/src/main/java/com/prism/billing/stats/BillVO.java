package com.prism.billing.stats;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 账单列表视图：账单字段 + 应用名（join t_app） */
@Data
public class BillVO {
    private Long id;
    private Long appId;
    private String appName;
    private String billMonth;
    private Long totalTokens;
    private Long callCount;
    private BigDecimal amount;
    private Integer status;
    private LocalDateTime createdAt;
}
