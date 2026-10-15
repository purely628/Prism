package com.prism.user.dto;

import com.prism.user.entity.AppEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 应用列表/详情视图：附带月已用 token 与配额进度 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AppVO {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Long id;
    private String appName;
    private String appKey;
    private Long monthlyQuota;
    private Integer rateLimit;
    private Integer status;
    /** 本月已用 token（Redis prism:quota:used，无则 0） */
    private Long usedTokens;
    /** 配额进度百分比（0~100，quota<=0 时 0） */
    private Integer quotaPercent;
    private String createdAt;

    public static AppVO of(AppEntity app, long usedTokens) {
        long quota = app.getMonthlyQuota() == null ? 0 : app.getMonthlyQuota();
        int percent = quota <= 0 ? 0 : (int) Math.min(100, usedTokens * 100 / quota);
        return new AppVO(
                app.getId(),
                app.getAppName(),
                app.getAppKey(),
                app.getMonthlyQuota(),
                app.getRateLimit(),
                app.getStatus(),
                usedTokens,
                percent,
                app.getCreatedAt() == null ? null : app.getCreatedAt().format(FMT));
    }
}
