package com.prism.gateway.filter;

import com.prism.common.result.ErrorCode;
import com.prism.common.result.Result;
import com.prism.gateway.support.Responses;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * /internal/** 前置拦截：必须在路由匹配之前生效。
 * GlobalFilter 只在命中路由后执行，而路由表里根本没有 /internal 路由
 * （否则会先 404），因此这里用最高优先级 WebFilter 兜住。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InternalRejectWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith("/internal")) {
            return Responses.writeResult(exchange, HttpStatus.FORBIDDEN, Result.fail(ErrorCode.FORBIDDEN));
        }
        return chain.filter(exchange);
    }
}
