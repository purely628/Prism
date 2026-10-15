package com.prism.billing.stats;

import lombok.Data;

/** 按模型聚合：{model, tokens} */
@Data
public class ModelStat {
    private String model;
    private Long tokens;
}
