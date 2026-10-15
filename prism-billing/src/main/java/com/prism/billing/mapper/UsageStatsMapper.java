package com.prism.billing.mapper;

import com.prism.billing.stats.AppStat;
import com.prism.billing.stats.CacheStat;
import com.prism.billing.stats.DailyStat;
import com.prism.billing.stats.ModelStat;
import com.prism.billing.stats.MonthAgg;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用量看板聚合 SQL（GROUP BY 无法用 LambdaQueryWrapper 表达，走注解 SQL）。
 * 时间过滤只用到 created_at（有 idx_time 索引），只查必要列。
 */
public interface UsageStatsMapper {

    @Select("SELECT DATE(created_at) AS day, IFNULL(SUM(total_tokens), 0) AS tokens, COUNT(*) AS calls " +
            "FROM t_usage_record WHERE created_at >= #{start} " +
            "GROUP BY DATE(created_at) ORDER BY day")
    List<DailyStat> dailyStats(@Param("start") LocalDateTime start);

    @Select("SELECT model, IFNULL(SUM(total_tokens), 0) AS tokens " +
            "FROM t_usage_record WHERE created_at >= #{start} " +
            "GROUP BY model ORDER BY tokens DESC")
    List<ModelStat> modelStats(@Param("start") LocalDateTime start);

    @Select("SELECT IFNULL(SUM(CASE WHEN cache_hit = 1 THEN 1 ELSE 0 END), 0) AS hits, COUNT(*) AS total " +
            "FROM t_usage_record WHERE created_at >= #{start}")
    CacheStat cacheStat(@Param("start") LocalDateTime start);

    /**
     * topApps：按应用聚合 token 并关联 t_app 取应用名。
     * 演示环境 user/billing/kb 共库，本服务直接 join t_app；
     * 生产拆库后应改为先聚合 app_id 再调 user 服务批量取名。
     */
    @Select("SELECT r.app_id AS appId, IFNULL(a.app_name, CONCAT('app-', r.app_id)) AS appName, " +
            "IFNULL(SUM(r.total_tokens), 0) AS tokens " +
            "FROM t_usage_record r LEFT JOIN t_app a ON a.id = r.app_id " +
            "WHERE r.created_at >= #{start} " +
            "GROUP BY r.app_id, a.app_name ORDER BY tokens DESC LIMIT 10")
    List<AppStat> topApps(@Param("start") LocalDateTime start);

    /** 月度按应用聚合（对账 + 出账共用）：走 (app_id, created_at) 索引 */
    @Select("SELECT app_id AS appId, IFNULL(SUM(prompt_tokens),0) AS promptTokens, " +
            "IFNULL(SUM(completion_tokens),0) AS completionTokens, " +
            "IFNULL(SUM(total_tokens),0) AS totalTokens, COUNT(*) AS calls " +
            "FROM t_usage_record WHERE created_at >= #{start} AND created_at < #{end} " +
            "GROUP BY app_id")
    List<MonthAgg> monthAggByApp(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
