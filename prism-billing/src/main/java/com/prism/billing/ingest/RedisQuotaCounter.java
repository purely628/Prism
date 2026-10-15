package com.prism.billing.ingest;

import com.prism.common.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/** Redis 实现：INCRBY 累加 + EXPIRE 40 天；对账覆写用 SET（保 TTL） */
@Component
@RequiredArgsConstructor
public class RedisQuotaCounter implements QuotaCounter {

    private final StringRedisTemplate redisTemplate;

    @Value("${prism.billing.quota-expire-days:40}")
    private long quotaExpireDays;

    @Override
    public void incrBy(long appId, String yyyyMM, long delta) {
        if (delta <= 0) {
            return;
        }
        String key = RedisKeys.quotaUsed(appId, yyyyMM);
        redisTemplate.opsForValue().increment(key, delta);
        redisTemplate.expire(key, Duration.ofDays(quotaExpireDays));
    }

    @Override
    public long get(long appId, String yyyyMM) {
        String val = redisTemplate.opsForValue().get(RedisKeys.quotaUsed(appId, yyyyMM));
        return val == null ? 0L : Long.parseLong(val);
    }

    @Override
    public void overwrite(long appId, String yyyyMM, long value) {
        redisTemplate.opsForValue().set(RedisKeys.quotaUsed(appId, yyyyMM), String.valueOf(value),
                Duration.ofDays(quotaExpireDays));
    }

    @Override
    public Set<Long> appIdsWithUsage(String yyyyMM) {
        Set<Long> appIds = new HashSet<>();
        String pattern = "prism:quota:used:*:" + yyyyMM;
        try (Cursor<String> cursor = redisTemplate.scan(ScanOptions.scanOptions().match(pattern).count(500).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next(); // prism:quota:used:{appId}:{yyyyMM}
                String[] parts = key.split(":");
                if (parts.length == 5) {
                    appIds.add(Long.parseLong(parts[3]));
                }
            }
        }
        return appIds;
    }
}
