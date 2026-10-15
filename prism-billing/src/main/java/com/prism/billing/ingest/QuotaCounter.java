package com.prism.billing.ingest;

import java.util.Set;

/**
 * 月度配额计数器（Redis prism:quota:used:{appId}:{yyyyMM}）：
 * billing 消费后累加（chat 预检读取）；对账时读 / 覆写。
 */
public interface QuotaCounter {

    /** 累加并刷新 TTL（40 天） */
    void incrBy(long appId, String yyyyMM, long delta);

    long get(long appId, String yyyyMM);

    /** 对账修正：以 DB 值覆写 Redis */
    void overwrite(long appId, String yyyyMM, long value);

    /** 扫描当月有计数器的全部 appId（对账用，SCAN 非阻塞） */
    Set<Long> appIdsWithUsage(String yyyyMM);
}
