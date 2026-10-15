package com.prism.billing.ingest;

import com.prism.common.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Redis 实现：prism:idem:usage:{msgId}，TTL 48h（契约） */
@Component
@RequiredArgsConstructor
public class RedisIdemGuard implements IdemGuard {

    private final StringRedisTemplate redisTemplate;

    @Value("${prism.billing.idem-ttl-days:2}")
    private long idemTtlDays;

    @Override
    public boolean seen(String msgId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeys.usageIdem(msgId)));
    }

    @Override
    public void mark(String msgId) {
        redisTemplate.opsForValue().setIfAbsent(RedisKeys.usageIdem(msgId), "1", Duration.ofDays(idemTtlDays));
    }
}
