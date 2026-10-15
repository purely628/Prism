package com.prism.billing.service;

import com.prism.billing.entity.ReconcileDiff;
import com.prism.billing.ingest.QuotaCounter;
import com.prism.billing.mapper.ReconcileDiffMapper;
import com.prism.billing.mapper.UsageStatsMapper;
import com.prism.billing.stats.MonthAgg;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 对账：当月 MySQL 流水汇总 vs Redis 配额计数器。
 * DB 是唯一事实源（唯一索引 + 事务），diff≠0 时落 t_reconcile_diff 留痕并把 Redis 覆写为 DB 值。
 * 差异来源：消息未消费/未 ACK 重投后幂等跳过但计数器累加时机不同、Redis 故障窗口丢写。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconcileService {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private final UsageStatsMapper statsMapper;
    private final QuotaCounter quotaCounter;
    private final ReconcileDiffMapper reconcileDiffMapper;

    /** @return 发现并修正的差异条数 */
    public int reconcileCurrentMonth() {
        LocalDate today = LocalDate.now();
        String month = today.format(MONTH);
        LocalDateTime start = today.withDayOfMonth(1).atStartOfDay();
        LocalDateTime end = today.plusDays(1).atStartOfDay();

        Map<Long, Long> dbByApp = new HashMap<>();
        for (MonthAgg agg : statsMapper.monthAggByApp(start, end)) {
            dbByApp.put(agg.getAppId(), orZero(agg.getTotalTokens()));
        }
        // 对账范围 = DB 有流水的应用 ∪ Redis 有计数的应用（单边缺失也算差异）
        Set<Long> appIds = new HashSet<>(dbByApp.keySet());
        appIds.addAll(quotaCounter.appIdsWithUsage(month));

        int diffCount = 0;
        for (Long appId : appIds) {
            long dbTokens = dbByApp.getOrDefault(appId, 0L);
            long redisTokens = quotaCounter.get(appId, month);
            if (dbTokens == redisTokens) {
                continue;
            }
            ReconcileDiff diff = new ReconcileDiff();
            diff.setReconcileDay(today);
            diff.setAppId(appId);
            diff.setDbTokens(dbTokens);
            diff.setRedisTokens(redisTokens);
            diff.setDiff(redisTokens - dbTokens);
            reconcileDiffMapper.insert(diff);
            quotaCounter.overwrite(appId, month, dbTokens);
            log.warn("对账修正 appId={} 月={} db={} redis={}（已按 DB 覆写）", appId, month, dbTokens, redisTokens);
            diffCount++;
        }
        log.info("对账完成 月={} 覆盖应用={} 差异={}", month, appIds.size(), diffCount);
        return diffCount;
    }

    private static long orZero(Long v) {
        return v == null ? 0L : v;
    }
}
