package com.prism.billing.stats;

import lombok.Data;

/** 按应用聚合（topApps）：{appId, appName, tokens} */
@Data
public class AppStat {
    private Long appId;
    private String appName;
    private Long tokens;
}
