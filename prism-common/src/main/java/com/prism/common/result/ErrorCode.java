package com.prism.common.result;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 全局错误码约定：
 * 0 成功；4xxxx 客户端错误；5xxxx 服务端错误
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    PARAM_ERROR(40001, "参数错误"),
    UNAUTHORIZED(40101, "未登录或登录已过期"),
    API_KEY_INVALID(40102, "无效的 API Key"),
    FORBIDDEN(40301, "无权限访问该资源"),
    NOT_FOUND(40401, "资源不存在"),
    CHANNEL_UNAVAILABLE(40402, "无可用模型渠道"),
    RATE_LIMITED(42901, "请求过于频繁，已触发限流"),
    QUOTA_EXCEEDED(42902, "本月配额已用尽"),
    UPSTREAM_ERROR(50201, "上游模型服务调用失败"),
    MQ_ERROR(50301, "消息投递失败"),
    INTERNAL_ERROR(50000, "服务内部错误");

    private final int code;
    private final String message;
}
