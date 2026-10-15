package com.prism.chat.service;

import com.prism.chat.entity.ModelChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 渠道路由：健康过滤（连续失败 ≥3 摘除，失败计数 key 5min TTL 过期自动恢复=半开）
 * + 加权随机选择。无可用渠道返回 Mono.empty()，由调用方转 502 channel_unavailable。
 */
@Slf4j
@Component
public class ChannelRouter {

    /** 连续失败达到该阈值即摘除（契约：3） */
    public static final int FAIL_THRESHOLD = 3;

    private final ChannelRepository repository;
    private final Random random;

    /** Spring 注入用（无 @Autowired 时多构造器会歧义） */
    @Autowired
    public ChannelRouter(ChannelRepository repository) {
        this(repository, new Random());
    }

    /** 测试可直接用本构造器注入固定种子，断言加权分布 */
    public ChannelRouter(ChannelRepository repository, Random random) {
        this.repository = repository;
        this.random = random;
    }

    /**
     * @param alias    对外模型名
     * @param excluded 故障转移过程中已失败、需要排除的渠道 ID
     */
    public Mono<ModelChannel> select(String alias, Set<Long> excluded) {
        return repository.loadEnabledChannels(alias)
                .flatMapMany(Flux::fromIterable)
                .filter(c -> c.getId() != null && !excluded.contains(c.getId()))
                .filterWhen(c -> repository.failCount(c.getId())
                        .map(fails -> fails < FAIL_THRESHOLD))
                .collectList()
                .flatMap(candidates -> candidates.isEmpty()
                        ? Mono.empty()
                        : Mono.just(weightedPick(candidates)));
    }

    private ModelChannel weightedPick(List<ModelChannel> candidates) {
        int total = candidates.stream()
                .mapToInt(this::weightOf)
                .sum();
        double point = random.nextDouble() * total;
        int acc = 0;
        for (ModelChannel c : candidates) {
            acc += weightOf(c);
            if (point < acc) {
                log.debug("weighted pick channel id={} weight={} total={}", c.getId(), weightOf(c), total);
                return c;
            }
        }
        return candidates.get(candidates.size() - 1); // 浮点边界兜底
    }

    private int weightOf(ModelChannel c) {
        return c.getWeight() == null || c.getWeight() <= 0 ? 1 : c.getWeight();
    }
}
