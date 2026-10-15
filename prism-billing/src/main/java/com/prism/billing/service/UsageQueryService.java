package com.prism.billing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.prism.billing.entity.App;
import com.prism.billing.entity.UsageRecord;
import com.prism.billing.ingest.QuotaCounter;
import com.prism.billing.mapper.AppMapper;
import com.prism.billing.mapper.UsageRecordMapper;
import com.prism.billing.mapper.UsageStatsMapper;
import com.prism.billing.stats.AppStat;
import com.prism.billing.stats.CacheStat;
import com.prism.billing.stats.DailyStat;
import com.prism.billing.stats.ModelStat;
import com.prism.common.exception.BizException;
import com.prism.common.result.ErrorCode;
import com.prism.common.result.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用量查询（契约 §6.3）：summary / page / quota。
 */
@Service
@RequiredArgsConstructor
public class UsageQueryService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private final UsageStatsMapper statsMapper;
    private final UsageRecordMapper usageRecordMapper;
    private final AppMapper appMapper;
    private final QuotaCounter quotaCounter;

    /** 看板汇总：days 天内 totalTokens/totalCalls/cacheHitRate/daily/byModel/topApps */
    public Map<String, Object> summary(int days) {
        int safeDays = Math.min(Math.max(days, 1), 90);
        LocalDate today = LocalDate.now();
        LocalDateTime start = today.minusDays(safeDays - 1L).atStartOfDay();

        List<DailyStat> dailyRows = statsMapper.dailyStats(start);
        List<ModelStat> byModel = statsMapper.modelStats(start);
        List<AppStat> topApps = statsMapper.topApps(start);
        CacheStat cache = statsMapper.cacheStat(start);

        // 补零：daily 覆盖窗口内每一天，前端画图不用自己填洞
        Map<String, DailyStat> byDay = new HashMap<>();
        dailyRows.forEach(d -> byDay.put(d.getDay(), d));
        List<Map<String, Object>> daily = new ArrayList<>();
        long totalTokens = 0;
        long totalCalls = 0;
        for (int i = safeDays - 1; i >= 0; i--) {
            String day = today.minusDays(i).format(DAY);
            DailyStat row = byDay.get(day);
            long tokens = row == null ? 0 : orZero(row.getTokens());
            long calls = row == null ? 0 : orZero(row.getCalls());
            totalTokens += tokens;
            totalCalls += calls;
            daily.add(Map.of("day", day, "tokens", tokens, "calls", calls));
        }

        long hits = cache == null ? 0 : orZero(cache.getHits());
        double cacheHitRate = totalCalls == 0 ? 0.0
                : Math.round(hits * 10000.0 / totalCalls) / 10000.0;

        Map<String, Object> result = new HashMap<>();
        result.put("totalTokens", totalTokens);
        result.put("totalCalls", totalCalls);
        result.put("cacheHitRate", cacheHitRate);
        result.put("daily", daily);
        result.put("byModel", byModel);
        result.put("topApps", topApps);
        return result;
    }

    /** 分页明细（appId 可选过滤） */
    public PageResult<UsageRecord> page(Long appId, long pageNum, long pageSize) {
        long safePageNum = Math.max(pageNum, 1);
        long safePageSize = Math.min(Math.max(pageSize, 1), 100);
        LambdaQueryWrapper<UsageRecord> wrapper = new LambdaQueryWrapper<UsageRecord>()
                .eq(appId != null, UsageRecord::getAppId, appId)
                .orderByDesc(UsageRecord::getCreatedAt);
        Page<UsageRecord> page = usageRecordMapper.selectPage(new Page<>(safePageNum, safePageSize), wrapper);
        return PageResult.of(page.getTotal(), safePageNum, safePageSize, page.getRecords());
    }

    /** 配额视图：t_app.monthly_quota + Redis 当月已用 */
    public Map<String, Object> quota(long appId) {
        App app = appMapper.selectById(appId);
        if (app == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "应用不存在: " + appId);
        }
        String month = LocalDate.now().format(MONTH);
        Map<String, Object> result = new HashMap<>();
        result.put("monthlyQuota", app.getMonthlyQuota());
        result.put("used", quotaCounter.get(appId, month));
        result.put("month", month);
        return result;
    }

    private static long orZero(Long v) {
        return v == null ? 0L : v;
    }
}
