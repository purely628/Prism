package com.prism.chat.service;

import com.prism.chat.entity.ModelChannel;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 渠道路由：加权随机分布、失败计数摘除、排除集、空候选。
 * Redis/DB 交互全部走 ChannelRepository stub（纯内存）。
 */
class ChannelRouterTest {

    private static ModelChannel channel(long id, String alias, int weight) {
        ModelChannel c = new ModelChannel();
        c.setId(id);
        c.setAlias(alias);
        c.setWeight(weight);
        c.setStatus(1);
        return c;
    }

    private static class StubRepo implements ChannelRepository {
        List<ModelChannel> channels = List.of();
        Map<Long, Long> failCounts = new HashMap<>();

        @Override
        public Mono<List<ModelChannel>> loadEnabledChannels(String alias) {
            return Mono.just(channels.stream().filter(c -> alias.equals(c.getAlias())).toList());
        }

        @Override
        public Mono<Long> failCount(long channelId) {
            return Mono.just(failCounts.getOrDefault(channelId, 0L));
        }

        @Override
        public Mono<Void> recordFail(long channelId) {
            failCounts.merge(channelId, 1L, Long::sum);
            return Mono.empty();
        }
    }

    @Test
    void weightedRandomMatchesWeightRatio() {
        StubRepo repo = new StubRepo();
        repo.channels = List.of(channel(1, "prism-demo", 9), channel(2, "prism-demo", 1));
        ChannelRouter router = new ChannelRouter(repo, new Random(42)); // 固定种子 → 断言完全可复现

        int first = 0;
        int trials = 4000;
        for (int i = 0; i < trials; i++) {
            if (router.select("prism-demo", Set.of()).block().getId() == 1L) {
                first++;
            }
        }
        double ratio = (double) first / trials;
        // 期望 0.9；固定种子下结果是常量，容差给足稳健性
        assertThat(ratio).isBetween(0.86, 0.94);
    }

    @Test
    void unhealthyChannelsAreSkipped() {
        StubRepo repo = new StubRepo();
        repo.channels = List.of(channel(1, "prism-demo", 9), channel(2, "prism-demo", 1));
        repo.failCounts.put(1L, 3L); // 连续失败 ≥ 3 → 摘除（计数 key 过期后自动恢复=半开）
        ChannelRouter router = new ChannelRouter(repo, new Random(7));

        for (int i = 0; i < 100; i++) {
            assertThat(router.select("prism-demo", Set.of()).block().getId()).isEqualTo(2L);
        }
    }

    @Test
    void boundaryFailCountTwoStillSelectable() {
        StubRepo repo = new StubRepo();
        repo.channels = List.of(channel(1, "prism-demo", 1));
        repo.failCounts.put(1L, 2L); // < 3 未达摘除阈值，仍可用
        ChannelRouter router = new ChannelRouter(repo, new Random(7));

        assertThat(router.select("prism-demo", Set.of()).block()).isNotNull();
    }

    @Test
    void excludedChannelsAreNotPicked() {
        StubRepo repo = new StubRepo();
        repo.channels = List.of(channel(1, "prism-demo", 9), channel(2, "prism-demo", 1));
        ChannelRouter router = new ChannelRouter(repo, new Random(1));

        assertThat(router.select("prism-demo", Set.of(1L)).block().getId()).isEqualTo(2L);
    }

    @Test
    void emptyWhenNoCandidateLeft() {
        StubRepo repo = new StubRepo();
        repo.channels = List.of(channel(1, "prism-demo", 1));
        ChannelRouter router = new ChannelRouter(repo, new Random(1));

        assertThat(router.select("prism-demo", Set.of(1L)).block()).isNull();       // 全被排除
        assertThat(router.select("unknown-model", Set.of()).block()).isNull();      // alias 无渠道
    }
}
