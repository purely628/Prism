package com.prism.billing.stats;

import lombok.Data;

/** 按应用聚合的月度用量（对账 / 出账共用）：{appId, promptTokens, completionTokens, totalTokens, calls} */
@Data
public class MonthAgg {
    private Long appId;
    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;
    private Long calls;
}
