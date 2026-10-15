package com.prism.gateway.handler;

import com.prism.common.result.ErrorCode;
import com.prism.common.result.Result;
import com.prism.gateway.support.Responses;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

/**
 * 网关层统一错误 JSON（优先级高于默认 DefaultErrorWebExceptionHandler）：
 * 404 路由不存在 / 下游连接失败 / 超时等，一律转成 Result 格式返回，
 * 避免 WebFlux 默认的 HTML/文本错误页破坏前端解析。
 * 响应已提交（如 SSE 流中途出错）时不接管，直接把异常继续抛出。
 */
@Slf4j
@Order(-2)
@Component
public class GatewayErrorWebExceptionHandler implements ErrorWebExceptionHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        HttpStatus status = resolveStatus(ex);
        Result<Void> body = failOf(status);
        log.warn("gateway error [{}] {}: {}", status.value(), exchange.getRequest().getPath().value(),
                ex.getMessage());
        return Responses.writeResult(exchange, status, body);
    }

    private HttpStatus resolveStatus(Throwable ex) {
        Throwable cause = ex;
        for (int i = 0; i < 5 && cause != null; i++) {
            if (cause instanceof TimeoutException) {
                return HttpStatus.GATEWAY_TIMEOUT;
            }
            if (cause instanceof ConnectException || cause.getClass().getName().endsWith("ConnectException")) {
                return HttpStatus.BAD_GATEWAY;
            }
            cause = cause.getCause();
        }
        if (ex instanceof ErrorResponseException e) {
            return HttpStatus.valueOf(e.getStatusCode().value());
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private Result<Void> failOf(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> Result.fail(ErrorCode.NOT_FOUND, "接口不存在");
            case UNAUTHORIZED -> Result.fail(ErrorCode.UNAUTHORIZED);
            case FORBIDDEN -> Result.fail(ErrorCode.FORBIDDEN);
            case TOO_MANY_REQUESTS -> Result.fail(ErrorCode.RATE_LIMITED);
            case BAD_GATEWAY, SERVICE_UNAVAILABLE, GATEWAY_TIMEOUT ->
                    Result.fail(ErrorCode.UPSTREAM_ERROR, "上游服务暂不可用，请稍后重试");
            default -> Result.fail(ErrorCode.INTERNAL_ERROR);
        };
    }
}
