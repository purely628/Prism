package com.prism.billing.ingest;

import com.prism.billing.entity.UsageRecord;
import com.prism.common.mq.UsageEvent;
import com.prism.common.util.SnowFlakeId;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 用量消费核心：幂等检查 → 攒批缓冲（内存，并发安全）→ 满 batch-size 条或每 flush-interval-ms
 * 批量 INSERT t_usage_record → 成功后逐条 INCRBY Redis 配额 + 标记幂等 → XACK。
 * 优雅停机（@PreDestroy）冲刷缓冲，避免丢尾部数据。
 */
@Slf4j
@Service
public class UsageIngestService {

    private static final DateTimeFormatter OCCURRED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private final IdemGuard idemGuard;
    private final QuotaCounter quotaCounter;
    private final UsageRecordStore recordStore;
    private final MessageAcker acker;
    private final SnowFlakeId snowFlakeId;

    @Value("${prism.billing.batch-size:500}")
    private int batchSize;

    /** 缓冲区：消费线程写入 / 定时任务冲刷，synchronized 保护 */
    private final List<BufferedEvent> buffer = new ArrayList<>();
    /** 同一批未落库消息的内存去重（幂等键在落库后才置位） */
    private final Set<String> pendingMsgIds = new HashSet<>();

    public UsageIngestService(IdemGuard idemGuard, QuotaCounter quotaCounter, UsageRecordStore recordStore,
                              MessageAcker acker, SnowFlakeId snowFlakeId) {
        this.idemGuard = idemGuard;
        this.quotaCounter = quotaCounter;
        this.recordStore = recordStore;
        this.acker = acker;
        this.snowFlakeId = snowFlakeId;
    }

    /**
     * 消费一条用量事件（Stream/Rabbit 两种消费实现共同入口）。
     *
     * @param streamId Redis Stream 消息 ID（rabbit 来源为 null）
     * @return true 已入缓冲；false 幂等跳过
     */
    public boolean ingest(UsageEvent event, RecordId streamId) {
        if (event == null || event.getMsgId() == null || event.getMsgId().isBlank()) {
            throw new IllegalArgumentException("usage event missing msgId");
        }
        if (idemGuard.seen(event.getMsgId())) {
            log.debug("幂等跳过重复消息 msgId={}", event.getMsgId());
            return false;
        }
        synchronized (buffer) {
            if (!pendingMsgIds.add(event.getMsgId())) {
                log.debug("缓冲内重复消息跳过 msgId={}", event.getMsgId());
                return false;
            }
            buffer.add(new BufferedEvent(toRecord(event), streamId));
            boolean shouldFlush = buffer.size() >= batchSize;
            if (shouldFlush) {
                buffer.notifyAll();
            }
        }
        // 攒批触发条件之一：条数满（不在此处同步冲刷，交给守护/定时线程，避免阻塞消费）
        flushIfFull();
        return true;
    }

    /** 攒批触发条件之二：时间到（@Scheduled 每 2s） */
    @Scheduled(fixedDelayString = "${prism.billing.flush-interval-ms:2000}")
    public void scheduledFlush() {
        flush();
    }

    /** 满 500 条立即冲刷（消费线程调用） */
    private void flushIfFull() {
        synchronized (buffer) {
            if (buffer.size() < batchSize) {
                return;
            }
        }
        flush();
    }

    /**
     * 冲刷缓冲：批量 INSERT → INCRBY 配额 → 标记幂等 → XACK（仅 Stream 来源）。
     * 落库抛异常时消息留在 PEL（未 ack），idle 超时后会被重新认领重试。
     */
    public void flush() {
        List<BufferedEvent> batch;
        synchronized (buffer) {
            if (buffer.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(buffer);
        }
        try {
            List<UsageRecord> records = batch.stream().map(BufferedEvent::record).toList();
            recordStore.saveAll(records);
        } catch (Exception e) {
            // 落库失败：还原缓冲等待下轮重试（幂等键未置位，重试安全）
            synchronized (buffer) {
                batch.addAll(buffer);
                buffer.clear();
                buffer.addAll(dedupe(batch));
            }
            throw e;
        }
        List<RecordId> streamIds = new ArrayList<>();
        for (BufferedEvent be : batch) {
            quotaCounter.incrBy(be.record().getAppId(), be.record().getCreatedAt().format(MONTH),
                    be.record().getTotalTokens());
            idemGuard.mark(be.record().getMsgId());
            if (be.streamId() != null) {
                streamIds.add(be.streamId());
            }
        }
        synchronized (buffer) {
            batch.forEach(be -> pendingMsgIds.remove(be.record().getMsgId()));
            buffer.removeAll(batch);
        }
        if (!streamIds.isEmpty()) {
            try {
                acker.ack(streamIds);
            } catch (Exception e) {
                log.error("XACK 失败（消息将经 XCLAIM 重投，DB 唯一索引兜底重复）: {}", e.getMessage());
            }
        }
        log.info("flush 批量落库完成，本批 {} 条", batch.size());
    }

    private List<BufferedEvent> dedupe(List<BufferedEvent> list) {
        Set<String> seen = new HashSet<>();
        List<BufferedEvent> out = new ArrayList<>(list.size());
        for (BufferedEvent be : list) {
            if (seen.add(be.record().getMsgId())) {
                out.add(be);
            }
        }
        return out;
    }

    /** 优雅停机：冲刷残留缓冲 */
    @PreDestroy
    public void shutdown() {
        try {
            flush();
        } catch (Exception e) {
            log.error("停机冲刷失败，残留 {} 条未落库", buffer.size(), e);
        }
    }

    private UsageRecord toRecord(UsageEvent event) {
        UsageRecord r = new UsageRecord();
        r.setId(snowFlakeId.nextId());
        r.setMsgId(event.getMsgId());
        r.setAppId(event.getAppId());
        r.setModel(event.getModel() == null ? "unknown" : event.getModel());
        r.setChannelId(event.getChannelId());
        r.setPromptTokens(nvl(event.getPromptTokens()));
        r.setCompletionTokens(nvl(event.getCompletionTokens()));
        r.setTotalTokens(nvl(event.getTotalTokens()));
        r.setLatencyMs(event.getLatencyMs() == null ? 0L : event.getLatencyMs());
        r.setCacheHit(Boolean.TRUE.equals(event.getCacheHit()));
        r.setCreatedAt(parseOccurredAt(event.getOccurredAt()));
        return r;
    }

    private LocalDateTime parseOccurredAt(String occurredAt) {
        if (occurredAt == null || occurredAt.isBlank()) {
            return LocalDateTime.now().withNano(0);
        }
        try {
            return LocalDateTime.parse(occurredAt, OCCURRED_AT);
        } catch (Exception e) {
            return LocalDateTime.now().withNano(0);
        }
    }

    private static int nvl(Integer v) {
        return v == null ? 0 : v;
    }

    private record BufferedEvent(UsageRecord record, RecordId streamId) { }
}
