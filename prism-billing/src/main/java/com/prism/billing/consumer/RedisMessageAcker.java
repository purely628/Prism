package com.prism.billing.consumer;

import com.prism.billing.ingest.MessageAcker;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** Stream 模式下的真实 XACK 实现（落库成功后由 UsageIngestService 回调） */
@Component
@ConditionalOnProperty(name = "prism.mq.mode", havingValue = "stream", matchIfMissing = true)
@RequiredArgsConstructor
public class RedisMessageAcker implements MessageAcker {

    private final StringRedisTemplate redisTemplate;

    @Value("${prism.billing.stream-key:prism:stream:usage}")
    private String streamKey;

    @Value("${prism.billing.group:billing}")
    private String group;

    @Override
    public void ack(List<RecordId> ids) {
        if (ids != null && !ids.isEmpty()) {
            redisTemplate.opsForStream().acknowledge(streamKey, group, ids.toArray(RecordId[]::new));
        }
    }
}
