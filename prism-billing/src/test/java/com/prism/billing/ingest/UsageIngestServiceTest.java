package com.prism.billing.ingest;

import com.prism.billing.entity.UsageRecord;
import com.prism.common.mq.UsageEvent;
import com.prism.common.util.SnowFlakeId;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 攒批/幂等逻辑（全部接口 stub，无 Redis/DB 依赖）。
 * batchSize 是 @Value 注入的字段，用 ReflectionTestUtils 设小值验证"满 N 条自动刷盘"。
 */
class UsageIngestServiceTest {

    private static class StubStore implements UsageRecordStore {
        final List<UsageRecord> saved = new ArrayList<>();

        @Override
        public void saveAll(List<UsageRecord> records) {
            saved.addAll(records);
        }
    }

    private static class StubGuard implements IdemGuard {
        final Set<String> seenSet = new HashSet<>();

        @Override
        public boolean seen(String msgId) {
            return seenSet.contains(msgId);
        }

        @Override
        public void mark(String msgId) {
            seenSet.add(msgId);
        }
    }

    private static class StubCounter implements QuotaCounter {
        long total = 0;

        @Override
        public void incrBy(long appId, String yyyyMM, long delta) {
            total += delta;
        }

        @Override
        public long get(long appId, String yyyyMM) {
            return total;
        }

        @Override
        public void overwrite(long appId, String yyyyMM, long value) {
            total = value;
        }

        @Override
        public Set<Long> appIdsWithUsage(String yyyyMM) {
            return Set.of();
        }
    }

    private static class StubAcker implements MessageAcker {
        int ackCount = 0;

        @Override
        public void ack(List<RecordId> ids) {
            ackCount += ids.size();
        }
    }

    private static UsageEvent event(String msgId, int tokens) {
        return UsageEvent.builder()
                .msgId(msgId).appId(1L).appKey("sk-test").model("prism-demo")
                .promptTokens(tokens / 2).completionTokens(tokens - tokens / 2).totalTokens(tokens)
                .latencyMs(100L).cacheHit(false).occurredAt("2026-10-11 12:00:00")
                .build();
    }

    @Test
    void 满批自动刷盘并累加配额() {
        StubStore store = new StubStore();
        StubGuard guard = new StubGuard();
        StubCounter counter = new StubCounter();
        StubAcker acker = new StubAcker();
        UsageIngestService service = new UsageIngestService(guard, counter, store, acker, new SnowFlakeId(1));
        ReflectionTestUtils.setField(service, "batchSize", 3);

        for (int i = 0; i < 3; i++) {
            assertTrue(service.ingest(event("m" + i, 100), RecordId.of("0-" + (i + 1))));
        }
        // 满 3 条自动触发批量落库 + 配额累加 + ACK
        assertEquals(3, store.saved.size());
        assertEquals(300, counter.total);
        assertEquals(3, acker.ackCount);
        assertTrue(guard.seen("m0"));
    }

    @Test
    void 重复消息被幂等跳过() {
        StubStore store = new StubStore();
        StubGuard guard = new StubGuard();
        UsageIngestService service = new UsageIngestService(guard, new StubCounter(), store, new StubAcker(), new SnowFlakeId(1));
        ReflectionTestUtils.setField(service, "batchSize", 100);

        assertTrue(service.ingest(event("dup", 50), RecordId.of("0-1")));
        guard.mark("dup"); // 模拟该消息此前已被处理
        assertFalse(service.ingest(event("dup", 50), RecordId.of("0-2")));
        assertEquals(0, store.saved.size());
    }

    @Test
    void 手动flush清空缓冲() {
        StubStore store = new StubStore();
        UsageIngestService service = new UsageIngestService(new StubGuard(), new StubCounter(), store, new StubAcker(), new SnowFlakeId(1));
        ReflectionTestUtils.setField(service, "batchSize", 100);

        service.ingest(event("a", 10), RecordId.of("0-3"));
        service.ingest(event("b", 20), RecordId.of("0-3"));
        service.flush();
        assertEquals(2, store.saved.size());
        service.flush();
        assertEquals(2, store.saved.size()); // 缓冲已空，重复 flush 不重复入库
    }
}
