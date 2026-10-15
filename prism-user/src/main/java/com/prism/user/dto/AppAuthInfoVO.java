package com.prism.user.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 应用鉴权信息（/internal/apps/bykey 响应 data，与网关 Redis hash 字段一一对应）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AppAuthInfoVO {

    private Long appId;
    private Long userId;
    /** 1 启用 0 禁用 */
    private Integer status;
    /** 月 token 配额 */
    private Long quota;
    /** 每秒请求数 */
    private Integer rate;
}
