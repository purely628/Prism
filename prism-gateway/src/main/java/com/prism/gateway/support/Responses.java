package com.prism.gateway.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.common.result.Result;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 网关层统一 JSON 响应写出：
 * - /api、/internal、网关错误 → Result 格式（控制台体系）；
 * - /v1 → OpenAI 风格 error 对象（客户端 SDK 兼容）。
 * 直接 writeWith 包装好的 buffer，不聚合、不缓冲响应流，SSE 透传不受影响。
 */
public final class Responses {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Responses() {
    }

    /** 写 Result 格式 JSON */
    public static Mono<Void> writeResult(ServerWebExchange exchange, HttpStatus status, Result<?> body) {
        return write(exchange, status, body);
    }

    /** 写 OpenAI 风格错误 JSON：{"error":{"message":"...","type":"..."}} */
    public static Mono<Void> writeOpenAiError(ServerWebExchange exchange, HttpStatus status,
                                              String message, String type) {
        return write(exchange, status, Map.of("error", Map.of("message", message, "type", type)));
    }

    private static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, Object body) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes;
        try {
            bytes = MAPPER.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            bytes = "{\"code\":50000,\"message\":\"gateway serialize error\"}"
                    .getBytes(StandardCharsets.UTF_8);
        }
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }
}
