package com.prism.gateway.dto;

import lombok.Data;

import java.util.Map;

/**
 * 应用鉴权信息：与 Redis hash prism:app:{appKey} 及用户服务
 * /internal/apps/bykey 响应 data 的字段一一对应（appId/userId/status/quota/rate）。
 */
@Data
public class AppAuthInfo {

    private Long appId;
    private Long userId;
    /** 1 启用 0 禁用 */
    private Integer status;
    /** 月 token 配额 */
    private Long quota;
    /** 每秒允许请求数（令牌桶速率） */
    private Integer rate;

    /**
     * 从 Redis hash 字段还原；任一字段缺失/非法返回 null（视为 miss，走回源）
     */
    public static AppAuthInfo fromHash(Map<String, String> fields) {
        if (fields == null
                || !fields.containsKey("appId") || !fields.containsKey("userId")
                || !fields.containsKey("status") || !fields.containsKey("quota")
                || !fields.containsKey("rate")) {
            return null;
        }
        try {
            AppAuthInfo info = new AppAuthInfo();
            info.setAppId(Long.parseLong(fields.get("appId")));
            info.setUserId(Long.parseLong(fields.get("userId")));
            info.setStatus(Integer.parseInt(fields.get("status")));
            info.setQuota(Long.parseLong(fields.get("quota")));
            info.setRate(Integer.parseInt(fields.get("rate")));
            return info;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public boolean valid() {
        return appId != null && userId != null && status != null && quota != null && rate != null;
    }

    /** rate 兜底：配置为空/非正数时按默认速率 10 处理，避免限流脚本除零 */
    public int safeRate() {
        return (rate == null || rate <= 0) ? 10 : rate;
    }
}
