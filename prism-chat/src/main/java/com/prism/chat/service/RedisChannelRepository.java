package com.prism.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.chat.entity.ModelChannel;
import com.prism.chat.mapper.ModelChannelMapper;
import com.prism.common.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;

/**
 * 渠道存取的 Redis/MySQL 实现。
 *
 * <p><b>阻塞隔离说明</b>：MyBatis-Plus 走 JDBC 是阻塞 API，WebFlux 事件循环
 * （Netty EventLoop）线程一旦被 JDBC 阻塞，整个服务的所有在途请求都会被拖住。
 * 因此所有 DB 调用必须用 Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())
 * 包装，把阻塞调用隔离到弹性线程池——这是本服务「纯响应式」约束下的硬规则。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisChannelRepository implements ChannelRepository {

    /** 渠道缓存 TTL（契约：60s，user 服务渠道变更时主动 DEL 双保险） */
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    /** 失败计数窗口（契约：5min，过期自动清零 → 渠道半开恢复探测） */
    private static final Duration FAIL_TTL = Duration.ofMinutes(5);

    private final ReactiveStringRedisTemplate redis;
    private final ModelChannelMapper channelMapper;
    private final ObjectMapper objectMapper;

    @Override
    public Mono<List<ModelChannel>> loadEnabledChannels(String alias) {
        return redis.opsForValue().get(RedisKeys.CHANNEL_CACHE)
                .flatMap(this::tryParse)
                .switchIfEmpty(Mono.defer(this::loadFromDb))
                .map(list -> list.stream()
                        .filter(c -> alias.equals(c.getAlias()))
                        .toList())
                .onErrorResume(e -> {
                    log.error("load channels failed for alias={}", alias, e);
                    return Mono.just(List.of());
                });
    }

    private Mono<List<ModelChannel>> tryParse(String json) {
        try {
            List<ModelChannel> list = objectMapper.readValue(json, new TypeReference<List<ModelChannel>>() {
            });
            return Mono.just(list);
        } catch (Exception e) {
            log.warn("channel cache json corrupt, fallback to db: {}", e.getMessage());
            return Mono.empty();
        }
    }

    private Mono<List<ModelChannel>> loadFromDb() {
        // 阻塞 JDBC 调用必须隔离到 boundedElastic（见类注释）
        return Mono.fromCallable(() ->
                        channelMapper.selectList(new LambdaQueryWrapper<ModelChannel>()
                                .eq(ModelChannel::getStatus, 1)))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(list -> {
                    if (list.isEmpty()) {
                        return Mono.just(list);
                    }
                    // 缓存里存全部启用渠道（单一共享 key），按 alias 内存过滤，
                    // 避免不同 alias 的请求互相覆盖回填导致缓存命中率退化
                    try {
                        String json = objectMapper.writeValueAsString(list);
                        return redis.opsForValue().set(RedisKeys.CHANNEL_CACHE, json, CACHE_TTL)
                                .thenReturn(list)
                                .onErrorResume(e -> {
                                    log.warn("backfill channel cache failed (ignored)", e);
                                    return Mono.just(list);
                                });
                    } catch (Exception e) {
                        log.warn("serialize channels failed (skip cache)", e);
                        return Mono.just(list);
                    }
                });
    }

    @Override
    public Mono<Long> failCount(long channelId) {
        return redis.opsForValue().get(RedisKeys.channelFail(channelId))
                .map(v -> {
                    try {
                        return Long.parseLong(v);
                    } catch (NumberFormatException e) {
                        return 0L;
                    }
                })
                .defaultIfEmpty(0L)
                .onErrorReturn(0L); // Redis 抖动时 fail-open：宁可放行，摘除只是增强手段
    }

    @Override
    public Mono<Void> recordFail(long channelId) {
        String key = RedisKeys.channelFail(channelId);
        return redis.opsForValue().increment(key)
                .flatMap(n -> redis.expire(key, FAIL_TTL).then())
                .onErrorResume(e -> {
                    log.warn("record channel fail counter failed (ignored): {}", e.getMessage());
                    return Mono.empty();
                });
    }
}
