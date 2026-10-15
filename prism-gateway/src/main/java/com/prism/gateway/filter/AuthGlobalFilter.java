package com.prism.gateway.filter;

import com.prism.common.constant.RedisKeys;
import com.prism.common.result.ErrorCode;
import com.prism.common.result.Result;
import com.prism.common.util.JwtUtil;
import com.prism.gateway.dto.AppAuthInfo;
import com.prism.gateway.support.Responses;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 全局鉴权/限流过滤器（不缓冲响应体，SSE 透传安全）：
 * <ul>
 *   <li>/internal/** ：外部一律 403（内部互调走服务直连，不经过网关）；</li>
 *   <li>/v1/** ：X-Api-Key 或 Bearer appKey → Redis app hash（miss 回源用户服务）→
 *       状态校验 → Lua 令牌桶限流 → 附加 X-App-Id / X-App-Key / X-App-Quota；错误用 OpenAI 风格；</li>
 *   <li>/api/**（除 /api/auth/login）：Bearer JWT 校验 → 附加 X-User-Id / X-Username；错误用 Result 风格。</li>
 * </ul>
 * 所有进入网关的请求先剥离外部伪造的内网透传头，再按鉴权结果重新附加。
 */
@Slf4j
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    public static final String HEADER_APP_ID = "X-App-Id";
    public static final String HEADER_APP_KEY = "X-App-Key";
    public static final String HEADER_APP_QUOTA = "X-App-Quota";
    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USERNAME = "X-Username";

    /** loadApp miss 时的哨兵对象（Mono&lt;Void&gt; 完成不带值，不能用 switchIfEmpty 区分 miss/成功） */
    private static final AppAuthInfo MISSING = new AppAuthInfo();

    /** 参数化解析用户服务回源响应 Result&lt;AppAuthInfo&gt; */
    private static final org.springframework.core.ParameterizedTypeReference<Result<AppAuthInfo>> APP_AUTH_TYPE =
            new org.springframework.core.ParameterizedTypeReference<>() {
            };

    private final ReactiveStringRedisTemplate redis;
    private final WebClient userWebClient;
    private final RedisScript<Long> rateLimitScript;
    private final String jwtSecret;

    public AuthGlobalFilter(ReactiveStringRedisTemplate redis,
                            @Qualifier("userWebClient") WebClient userWebClient,
                            @Qualifier("rateLimitScript") RedisScript<Long> rateLimitScript,
                            @Value("${prism.jwt.secret}") String jwtSecret) {
        this.redis = redis;
        this.userWebClient = userWebClient;
        this.rateLimitScript = rateLimitScript;
        this.jwtSecret = jwtSecret;
    }

    @Override
    public int getOrder() {
        return -100;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        ServerWebExchange clean = stripTrustedHeaders(exchange);

        // 1. /internal/**：网关可达即视为外部请求，直接 403
        if (path.startsWith("/internal")) {
            return Responses.writeResult(clean, HttpStatus.FORBIDDEN, Result.fail(ErrorCode.FORBIDDEN));
        }
        // 2. OpenAI 兼容路径：API Key 鉴权 + 令牌桶限流
        if (path.startsWith("/v1/")) {
            return authApiKey(clean, chain);
        }
        // 3. 控制台路径：JWT 鉴权（登录接口除外）
        if (path.startsWith("/api/")) {
            return authJwt(clean, chain, path);
        }
        return chain.filter(clean);
    }

    // ---------------------------------------------------------------- /v1/** API Key

    private Mono<Void> authApiKey(ServerWebExchange exchange, GatewayFilterChain chain) {
        String appKey = extractApiKey(exchange.getRequest());
        if (appKey == null || appKey.isBlank()) {
            return Responses.writeOpenAiError(exchange, HttpStatus.UNAUTHORIZED,
                    "Invalid API key", "invalid_request_error");
        }
        return loadApp(appKey)
                .defaultIfEmpty(MISSING)
                .flatMap(app -> {
                    // 查无此 key / 回源仍 miss / 应用被禁用 → 一律 401 invalid_request_error
                    if (app == MISSING || app.getStatus() == null || app.getStatus() != 1) {
                        return Responses.writeOpenAiError(exchange, HttpStatus.UNAUTHORIZED,
                                "Invalid API key", "invalid_request_error");
                    }
                    // 鉴权通过后执行令牌桶限流，再透传给下游
                    return tryAcquire(appKey, app.safeRate())
                            .flatMap(allowed -> Boolean.TRUE.equals(allowed)
                                    ? chain.filter(withAppHeaders(exchange, app, appKey))
                                    : Responses.writeOpenAiError(exchange, HttpStatus.TOO_MANY_REQUESTS,
                                            "Rate limit exceeded, please retry later", "rate_limit_error"));
                })
                .onErrorResume(e -> {
                    if (e instanceof AppLookupException lookup && lookup.status() < 500) {
                        // 用户服务明确 4xx（key 不存在）→ 无效 key
                        return Responses.writeOpenAiError(exchange, HttpStatus.UNAUTHORIZED,
                                "Invalid API key", "invalid_request_error");
                    }
                    log.error("gateway /v1 auth error, appKey={}", mask(appKey), e);
                    return Responses.writeOpenAiError(exchange, HttpStatus.INTERNAL_SERVER_ERROR,
                            "Gateway upstream lookup failed", "server_error");
                });
    }

    /** X-Api-Key 优先，其次 Authorization: Bearer sk-... */
    private String extractApiKey(ServerHttpRequest request) {
        String key = request.getHeaders().getFirst("X-Api-Key");
        if (key != null && !key.isBlank()) {
            return key.trim();
        }
        String auth = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return auth.substring(7).trim();
        }
        return null;
    }

    // ---------------------------------------------------------------- app hash 读取与回源

    private Mono<AppAuthInfo> loadApp(String appKey) {
        return redis.<String, String>opsForHash().entries(RedisKeys.appInfo(appKey))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                // fromHash 对缺失字段返回 null，必须用 mapNotNull（map 返回 null 会 NPE）
                .mapNotNull(AppAuthInfo::fromHash)
                // Redis 异常降级为 miss，走用户服务回源（网关不因 Redis 抖动拒绝全部流量）
                .onErrorResume(e -> {
                    log.warn("read app hash failed, fallback to user service: {}", e.getMessage());
                    return Mono.empty();
                })
                .switchIfEmpty(Mono.defer(() -> fetchAppFromUserService(appKey)));
    }

    private Mono<AppAuthInfo> fetchAppFromUserService(String appKey) {
        return userWebClient.get()
                .uri(uri -> uri.path("/internal/apps/bykey")
                        .queryParam("appKey", appKey).build())
                .retrieve()
                .onStatus(status -> status.is4xxClientError(), resp -> resp.releaseBody()
                        .then(Mono.error(new AppLookupException(resp.statusCode().value()))))
                .onStatus(status -> status.is5xxServerError(), resp -> resp.releaseBody()
                        .then(Mono.error(new AppLookupException(resp.statusCode().value()))))
                .bodyToMono(APP_AUTH_TYPE)
                .timeout(Duration.ofSeconds(3))
                // 查无此 key 时 data 为 null，mapNotNull 保证不向 map 传 null
                .mapNotNull(Result::getData)
                // 回源成功写回 Redis hash（无 TTL，常驻；禁用/删除时由 user 服务负责清）
                .flatMap(app -> cacheApp(appKey, app).thenReturn(app));
    }

    private Mono<Boolean> cacheApp(String appKey, AppAuthInfo app) {
        Map<String, String> hash = new HashMap<>();
        hash.put("appId", String.valueOf(app.getAppId()));
        hash.put("userId", String.valueOf(app.getUserId()));
        hash.put("status", String.valueOf(app.getStatus()));
        hash.put("quota", String.valueOf(app.getQuota()));
        hash.put("rate", String.valueOf(app.getRate()));
        return redis.opsForHash().putAll(RedisKeys.appInfo(appKey), hash);
    }

    // ---------------------------------------------------------------- Lua 令牌桶

    private Mono<Boolean> tryAcquire(String appKey, int rate) {
        long capacity = rate * 2L;
        List<String> keys = List.of(RedisKeys.rateLimit(appKey));
        List<Object> args = List.of(
                String.valueOf(rate),
                String.valueOf(capacity),
                String.valueOf(System.currentTimeMillis()),
                "1");
        return redis.execute(rateLimitScript, keys, args)
                .next()
                .map(n -> n != null && n == 1L)
                // Redis 不可用时放行（fail-open）：限流是保护手段而非鉴权，不能因缓存故障拒绝全部请求
                .onErrorResume(e -> {
                    log.warn("rate limit script failed, fail-open: {}", e.getMessage());
                    return Mono.just(true);
                });
    }

    private ServerWebExchange withAppHeaders(ServerWebExchange exchange, AppAuthInfo app, String appKey) {
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header(HEADER_APP_ID, String.valueOf(app.getAppId()))
                .header(HEADER_APP_KEY, appKey)
                .header(HEADER_APP_QUOTA, String.valueOf(app.getQuota()))
                .build();
        return exchange.mutate().request(mutated).build();
    }

    // ---------------------------------------------------------------- /api/** JWT

    private Mono<Void> authJwt(ServerWebExchange exchange, GatewayFilterChain chain, String path) {
        if ("/api/auth/login".equals(path)) {
            return chain.filter(exchange);
        }
        String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth == null || !auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Responses.writeResult(exchange, HttpStatus.UNAUTHORIZED, Result.fail(ErrorCode.UNAUTHORIZED));
        }
        String token = auth.substring(7).trim();
        try {
            Claims claims = JwtUtil.parse(jwtSecret, token);
            long uid = extractUid(claims);
            String username = String.valueOf(claims.get("username"));
            ServerHttpRequest mutated = exchange.getRequest().mutate()
                    .header(HEADER_USER_ID, String.valueOf(uid))
                    .header(HEADER_USERNAME, username)
                    .build();
            return chain.filter(exchange.mutate().request(mutated).build());
        } catch (Exception e) {
            log.debug("jwt parse rejected: {}", e.getMessage());
            return Responses.writeResult(exchange, HttpStatus.UNAUTHORIZED, Result.fail(ErrorCode.UNAUTHORIZED));
        }
    }

    private long extractUid(Claims claims) {
        Object uid = claims.get("uid");
        if (uid instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(claims.getSubject());
    }

    // ---------------------------------------------------------------- common

    /** 剥离外部请求携带的内网透传头，防止绕过网关伪造身份 */
    private ServerWebExchange stripTrustedHeaders(ServerWebExchange exchange) {
        ServerHttpRequest mutated = exchange.getRequest().mutate().headers(headers -> {
            headers.remove(HEADER_USER_ID);
            headers.remove(HEADER_USERNAME);
            headers.remove(HEADER_APP_ID);
            headers.remove(HEADER_APP_KEY);
            headers.remove(HEADER_APP_QUOTA);
        }).build();
        return exchange.mutate().request(mutated).build();
    }

    private String mask(String appKey) {
        if (appKey == null || appKey.length() < 12) {
            return "***";
        }
        return appKey.substring(0, 12) + "***";
    }

    /** 用户服务回源失败信号：携带 HTTP 状态码，4xx 视为 key 无效，其余视为网关侧 5xx */
    private static final class AppLookupException extends RuntimeException {
        private final int status;

        private AppLookupException(int status) {
            super("user service lookup http " + status);
            this.status = status;
        }

        int status() {
            return status;
        }
    }
}
