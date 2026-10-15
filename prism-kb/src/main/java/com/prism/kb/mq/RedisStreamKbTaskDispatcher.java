package com.prism.kb.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 本地模式实现：XADD prism:stream:kb（MAXLEN 10000 削堆积），
 * 消费组 agent-service 由 Python 服务创建并阻塞读取。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "prism.mq.mode", havingValue = "stream", matchIfMissing = true)
public class RedisStreamKbTaskDispatcher implements KbTaskDispatcher {

    public static final String STREAM_KEY = "prism:stream:kb";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void dispatch(long kbId, long docId) {
        try {
            String json = objectMapper.writeValueAsString(new TaskMessage(kbId, docId));
            RecordId id = redisTemplate.opsForStream()
                    .add(StreamRecords.newRecord().in(STREAM_KEY).ofMap(java.util.Map.of("payload", json)),
                            XAddOptions.maxlen(10000).approximateTrimming(true));
            log.info("kb 解析任务已派发 stream={} id={} docId={} kbId={}", STREAM_KEY, id, docId, kbId);
        } catch (Exception e) {
            log.error("kb 任务派发失败（文档将停留在 PENDING，可重新上传触发）docId={} kbId={}", docId, kbId, e);
        }
    }

    record TaskMessage(long kbId, long docId) {
    }
}
