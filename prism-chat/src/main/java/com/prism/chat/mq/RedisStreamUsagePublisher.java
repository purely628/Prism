package com.prism.chat.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.common.mq.UsageEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 默认实现：Redis Stream。
 * XADD prism:stream:usage MAXLEN ~10000（近似裁剪，兼顾性能），
 * billing 以消费组 billing 消费（CONTRACTS §5）。
 */
@Slf4j
@RequiredArgsConstructor
public class RedisStreamUsagePublisher implements UsageEventPublisher {

    public static final String STREAM_KEY = "prism:stream:usage";
    public static final long MAXLEN = 10_000;

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Override
    public Mono<Void> publish(UsageEvent event) {
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            return Mono.error(new IllegalStateException("serialize usage event failed", e));
        }
        MapRecord<String, String, String> record = StreamRecords.newRecord()
                .in(STREAM_KEY)
                .ofMap(Map.of("payload", json));
        return redis.opsForStream()
                .add(record, RedisStreamCommands.XAddOptions.maxlen(MAXLEN).approximateTrimming(true))
                .doOnNext(id -> log.info("usage event published to stream: msgId={} entryId={}",
                        event.getMsgId(), id.getValue()))
                .then();
    }
}
