package com.prism.chat.error;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * WebFlux 全局异常兜底：任何未处理异常 → OpenAI error JSON（正确 HTTP 状态码）。
 * 仅在响应未提交时生效；SSE 已开始后的错误由 ChatCompletionsController
 * 以 SSE error 数据事件收尾（状态码那时已无法更改）。
 * Order(-2) 优先于 Boot 默认的 DefaultErrorWebExceptionHandler(-1)。
 */
@Slf4j
@Configuration
@Order(-2)
public class OpenAiErrorWebExceptionHandler implements ErrorWebExceptionHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.error(ex); // 已提交（流已开），交给传输层断开，避免二次写
        }
        OpenAiErrors.Spec spec = OpenAiErrors.spec(ex);
        if (spec.status() == HttpStatus.INTERNAL_SERVER_ERROR) {
            log.error("unhandled exception on {} {}", exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath(), ex);
        } else {
            log.warn("request rejected: {} {} -> {} {}",
                    exchange.getRequest().getMethod(), exchange.getRequest().getPath(),
                    spec.status(), spec.code());
        }
        response.setStatusCode(spec.status());
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory().wrap(OpenAiErrors.bodyBytes(spec));
        return response.writeWith(Flux.just(buffer));
    }
}
