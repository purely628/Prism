package com.prism.billing.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.billing.ingest.UsageIngestService;
import com.prism.billing.service.FailedMessageService;
import com.prism.common.mq.UsageEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Redis Stream 用量消费者（默认实现，契约 §5）：
 * - SmartLifecycle 启动独立消费线程：XGROUP CREATE（BUSYGROUP 忽略）
 * - 先 XPENDING + XCLAIM 认领 idle > 5min 的消息（上一实例崩溃遗留的未 ack 消息，简单故障恢复；
 *   生产可用 XAUTOCLAIM，本机 Redis 5.0 兼容起见用 XPENDING+XCLAIM）
 * - 循环 XREADGROUP GROUP billing {consumer} BLOCK 3000 COUNT 16 >
 * - 逐条处理：JSON 反序列化 → UsageIngestService（幂等 + 缓冲）；
 *   解析失败的毒消息落 t_failed_message 后直接 XACK（防死循环）；
 *   处理异常不 XACK，留在 PEL 待 idle 超时重新认领。
 *   正常消息的 XACK 由 UsageIngestService 在批量落库成功后统一回调。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "prism.mq.mode", havingValue = "stream", matchIfMissing = true)
public class RedisStreamUsageConsumer implements UsageConsumer, org.springframework.context.SmartLifecycle {

    private final StringRedisTemplate redisTemplate;
    private final UsageIngestService ingestService;
    private final FailedMessageService failedMessageService;
    private final ObjectMapper objectMapper;

    @Value("${prism.billing.stream-key:prism:stream:usage}")
    private String streamKey;

    @Value("${prism.billing.group:billing}")
    private String group;

    @Value("${prism.billing.consumer-name:billing-1}")
    private String consumerName;

    @Value("${prism.billing.claim-idle-minutes:5}")
    private long claimIdleMinutes;

    private volatile boolean running = false;
    private Thread workerThread;

    public RedisStreamUsageConsumer(StringRedisTemplate redisTemplate, UsageIngestService ingestService,
                                     FailedMessageService failedMessageService, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.ingestService = ingestService;
        this.failedMessageService = failedMessageService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void start() {
        running = true;
        workerThread = new Thread(this::runLoop, "usage-stream-consumer");
        workerThread.setDaemon(false);
        workerThread.start();
    }

    @Override
    public void stop() {
        running = false;
        if (workerThread != null) {
            try {
                workerThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void runLoop() {
        createGroup();
        claimStalePendingMessages();
        log.info("usage stream 消费者已启动: stream={} group={} consumer={}", streamKey, group, consumerName);
        StreamOperations<String, Object, Object> ops = redisTemplate.opsForStream();
        while (running) {
            try {
                List<MapRecord<String, Object, Object>> records = ops.read(
                        Consumer.from(group, consumerName),
                        StreamReadOptions.empty().count(16).block(Duration.ofSeconds(3)),
                        StreamOffset.create(streamKey, ReadOffset.lastConsumed()));
                if (records == null || records.isEmpty()) {
                    continue;
                }
                for (MapRecord<String, Object, Object> record : records) {
                    handleRecord(record);
                }
            } catch (Exception e) {
                if (running) {
                    log.error("usage stream 读取异常，3s 后重试: {}", e.getMessage());
                    sleepQuietly(3000);
                }
            }
        }
        log.info("usage stream 消费者已停止");
    }

    private void createGroup() {
        try {
            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0"), group);
            log.info("已创建消费组 {}/{}", streamKey, group);
        } catch (RedisSystemException e) {
            // BUSYGROUP：组已存在，忽略（消息不会重复消费）
            log.info("消费组已存在（BUSYGROUP 忽略）: {}/{}", streamKey, group);
        }
    }

    /** 简单故障恢复：认领 pending 中 idle > claimIdleMinutes 的消息（其他消费者崩溃遗留） */
    private void claimStalePendingMessages() {
        try {
            StreamOperations<String, Object, Object> ops = redisTemplate.opsForStream();
            PendingMessages pending = ops.pending(streamKey, group, Range.unbounded(), 1000);
            if (pending == null || pending.isEmpty()) {
                return;
            }
            List<RecordId> staleIds = pending.stream()
                    .filter(pm -> pm.getElapsedTimeSinceLastDelivery().compareTo(Duration.ofMinutes(claimIdleMinutes)) > 0)
                    .map(PendingMessage::getId)
                    .toList();
            if (staleIds.isEmpty()) {
                return;
            }
            List<MapRecord<String, Object, Object>> claimed = ops.claim(streamKey, group, consumerName,
                    Duration.ofMinutes(claimIdleMinutes), staleIds.toArray(RecordId[]::new));
            log.info("XCLAIM 认领 idle>{}min 的待处理消息 {} 条", claimIdleMinutes, claimed.size());
            claimed.forEach(this::handleRecord);
        } catch (Exception e) {
            log.warn("认领 pending 消息失败（不影响主流程）: {}", e.getMessage());
        }
    }

    private void handleRecord(MapRecord<String, Object, Object> record) {
        String raw = extractPayload(record);
        try {
            UsageEvent event = objectMapper.readValue(raw, UsageEvent.class);
            ingestService.ingest(event, record.getId());
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException e) {
            // 毒消息（缺 payload / JSON 损坏）：落 t_failed_message 兜底后 XACK，防止无限重投
            failedMessageService.save(raw, e.getMessage());
            redisTemplate.opsForStream().acknowledge(streamKey, group, record.getId());
        } catch (Exception e) {
            // 幂等检查等瞬时失败（如 Redis 抖动）：不 ack，留在 PEL，idle>5min 后重新认领重试
            log.error("处理消息失败（保留 PEL 待重投）id={} err={}", record.getId(), e.getMessage());
        }
    }

    /** 消息体约定：字段 payload 为 UsageEvent JSON（chat 服务 XADD ... payload '{...}'） */
    private String extractPayload(MapRecord<String, Object, Object> record) {
        Map<Object, Object> value = record.getValue();
        Object payload = value.get("payload");
        if (payload != null) {
            return payload.toString();
        }
        throw new IllegalArgumentException("消息缺少 payload 字段: id=" + record.getId());
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
