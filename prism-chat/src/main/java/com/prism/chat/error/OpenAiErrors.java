package com.prism.chat.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.common.exception.BizException;
import com.prism.common.result.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一把内部异常翻译成 OpenAI 风格 error 对象：
 * {"error":{"message":"...","type":"...","code":"..."}}
 * （契约 §6.2：/v1/** 错误用 OpenAI 格式，不用 Result）。
 */
@Slf4j
public final class OpenAiErrors {

    public record Spec(HttpStatus status, String message, String type, String code) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OpenAiErrors() {
    }

    public static Spec spec(Throwable error) {
        BizException biz = findBiz(error);
        if (biz != null) {
            return switch (biz.getErrorCode()) {
                case PARAM_ERROR -> new Spec(HttpStatus.BAD_REQUEST, biz.getMessage(),
                        "invalid_request_error", "invalid_request");
                case UNAUTHORIZED, API_KEY_INVALID -> new Spec(HttpStatus.UNAUTHORIZED, biz.getMessage(),
                        "invalid_request_error", "invalid_api_key");
                case RATE_LIMITED -> new Spec(HttpStatus.TOO_MANY_REQUESTS, biz.getMessage(),
                        "rate_limit_error", "rate_limit_exceeded");
                case QUOTA_EXCEEDED -> new Spec(HttpStatus.TOO_MANY_REQUESTS,
                        biz.getMessage() == null ? "本月配额已用尽" : biz.getMessage(),
                        "rate_limit_error", "quota_exceeded");
                case CHANNEL_UNAVAILABLE -> new Spec(HttpStatus.BAD_GATEWAY, biz.getMessage(),
                        "api_error", "channel_unavailable");
                case UPSTREAM_ERROR -> new Spec(HttpStatus.BAD_GATEWAY, biz.getMessage(),
                        "api_error", "upstream_error");
                default -> new Spec(HttpStatus.INTERNAL_SERVER_ERROR, biz.getMessage(),
                        "api_error", "internal_error");
            };
        }
        if (error instanceof ServerWebInputException || error instanceof DecodingException) {
            return new Spec(HttpStatus.BAD_REQUEST,
                    "请求体缺失或不是合法 JSON：应满足 OpenAI chat/completions 请求结构",
                    "invalid_request_error", "invalid_request");
        }
        if (error instanceof ResponseStatusException rse) {
            HttpStatus status = HttpStatus.resolve(rse.getStatusCode().value());
            status = status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status;
            String type = status.is4xxClientError() ? "invalid_request_error" : "api_error";
            return new Spec(status, rse.getReason() == null ? status.getReasonPhrase() : rse.getReason(),
                    type, "http_" + status.value());
        }
        return new Spec(HttpStatus.INTERNAL_SERVER_ERROR, "服务内部错误", "api_error", "internal_error");
    }

    /** 错误体 JSON 字符串：用于 SSE 中途失败的 data 事件 */
    public static String sseErrorPayload(Throwable error) {
        Spec spec = spec(error);
        return toJson(spec);
    }

    public static byte[] bodyBytes(Spec spec) {
        try {
            return MAPPER.writeValueAsBytes(errorMap(spec));
        } catch (Exception e) {
            return ("{\"error\":{\"message\":\"serialize error failed\",\"type\":\"api_error\",\"code\":\"internal_error\"}}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static String toJson(Spec spec) {
        try {
            return MAPPER.writeValueAsString(errorMap(spec));
        } catch (Exception e) {
            return "{\"error\":{\"message\":\"serialize error failed\",\"type\":\"api_error\",\"code\":\"internal_error\"}}";
        }
    }

    private static Map<String, Object> errorMap(Spec spec) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", spec.message());
        error.put("type", spec.type());
        error.put("code", spec.code());
        return Map.of("error", error);
    }

    /** 沿 cause 链找 BizException（响应式栈里常被包一层） */
    private static BizException findBiz(Throwable e) {
        Throwable cur = e;
        for (int i = 0; cur != null && i < 10; i++) {
            if (cur instanceof BizException biz) {
                return biz;
            }
            cur = cur.getCause();
        }
        return null;
    }
}
