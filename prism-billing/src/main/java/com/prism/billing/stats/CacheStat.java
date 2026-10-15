package com.prism.billing.stats;

import lombok.Data;

/** 缓存命中统计：{hits, total}，cacheHitRate = hits / total */
@Data
public class CacheStat {
    private Long hits;
    private Long total;
}
