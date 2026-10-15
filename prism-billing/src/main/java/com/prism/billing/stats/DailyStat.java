package com.prism.billing.stats;

import lombok.Data;

/** 按天聚合：{day, tokens, calls} */
@Data
public class DailyStat {
    private String day;
    private Long tokens;
    private Long calls;
}
