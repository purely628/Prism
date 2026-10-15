package com.prism.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.chat.dto.ChatCall;
import com.prism.chat.dto.ChatMessage;
import com.prism.chat.llm.LlmClientFactory;
import com.prism.chat.llm.UpstreamChunk;
import com.prism.chat.llm.UsageInfo;
import com.prism.chat.mq.UsageEventPublisher;
import com.prism.chat.util.PromptNormalizer;
import com.prism.chat.util.TokenEstimator;
import com.prism.common.constant.RedisKeys;
import com.prism.common.exception.BizException;
import com.prism.common.mq.UsageEvent;
import com.prism.common.result.ErrorCode;
import com.prism.common.util.SnowFlakeId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.PrematureCloseException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 推理主链路编排（纯响应式）：
 * 配额预检 → 语义缓存查询 → 渠道路由/故障转移 → 上游流式调用 →
 * OpenAI 格式 chunk 组装 / 聚合 → 用量事件旁路发布（doFinally）。
 *
 * <p>流式路径下，错误发生在首个数据帧之前 → 异常上抛（全局异常处理器给正确 HTTP 状态码）；
 * 已开始输出后 → 只能以 SSE 数据事件（OpenAI error 对象）收尾，HTTP 状态码无法再改。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatOrchestrator {

    /** 故障转移最多换 2 次渠道（连同首次共 3 次尝试），对调用方透明 */
    static final int MAX_RETRIES = 2;
    /** 语义缓存回放：6 字符/片、30ms 间隔，模拟真实流式节奏 */
    private static final int REPLAY_SLICE = 6;
    private static final java.time.Duration REPLAY_DELAY = java.time.Duration.ofMillis(30);
    private static final DateTimeFormatter OCCURRED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ReactiveStringRedisTemplate redis;
    private final SemanticCacheService cacheService;
    private final ChannelRouter channelRouter;
    private final ChannelRepository channelRepository;
    private final LlmClientFactory llmClients;
    private final UsageEventPublisher usagePublisher;
    private final SnowFlakeId snowflake;
    private final ObjectMapper objectMapper;

    // ==================== 对外入口 ====================

    /** 流式：输出 SSE data 负载序列（chunk JSON ... → usage JSON → [DONE]） */
    public Flux<String> stream(ChatCall call) {
        return prepare(call).flatMapMany(p -> p.lookup().hit()
                ? replayStream(call, p)
                : upstreamStream(call, p));
    }

    /** 非流式：聚合为 OpenAI 单 JSON（Map 结构，避免 String 二次转义问题） */
    public Mono<Map<String, Object>> aggregate(ChatCall call) {
        return prepare(call).flatMap(p -> p.lookup().hit()
                ? aggregateReplay(call, p)
                : aggregateUpstream(call, p));
    }

    private record Prepared(long startedAt, long msgId, String normalizedPrompt,
                            SemanticCacheService.CacheLookup lookup) {
    }

    /**
     * 预检阶段（配额 + 语义缓存查询）。
     * 失败在首个输出帧之前抛出，由全局异常处理器转成 429/500 等正确状态码。
     */
    private Mono<Prepared> prepare(ChatCall call) {
        long startedAt = System.currentTimeMillis();
        long msgId = snowflake.nextId();
        String normalized = PromptNormalizer.lastUserNormalized(call.messages());
        return quotaGuard(call)
                .then(cacheService.lookup(call.model(), normalized))
                .map(lookup -> new Prepared(startedAt, msgId, normalized, lookup));
    }

    // ==================== 配额预检 ====================

    /**
     * 配额预检（软限制）：只比较「历史已用 ≥ 月配额」，不看本次请求将产生的增量。
     *
     * <p>权衡：本地 token 估算与上游真实计量存在偏差，若在入口预扣增量，
     * 估算偏高会误杀小配额应用、偏低又形同虚设；因此预检只挡"已经超额"的请求，
     * 实际累加由 billing 消费用量事件后 INCRBY 完成（事后兜底）。
     * 最坏情况是超额当月的最后一次请求略微超支，由每日对账与月账单环节呈现。</p>
     */
    private Mono<Long> quotaGuard(ChatCall call) {
        String month = DateTimeFormatter.ofPattern("yyyyMM").format(YearMonth.now());
        String key = RedisKeys.quotaUsed(call.appId(), month);
        return redis.opsForValue().get(key)
                .map(v -> {
                    try {
                        return Long.parseLong(v);
                    } catch (NumberFormatException e) {
                        return 0L;
                    }
                })
                .defaultIfEmpty(0L)
                .flatMap(used -> {
                    if (used >= call.quota()) {
                        log.info("quota exceeded: appId={} used={} quota={}", call.appId(), used, call.quota());
                        return Mono.error(new BizException(ErrorCode.QUOTA_EXCEEDED, "本月配额已用尽"));
                    }
                    return Mono.just(used);
                });
    }

    // ==================== 上游调用（路由 + 故障转移） ====================

    private Flux<UpstreamChunk> invokeUpstream(ChatCall call,
                                               StringBuilder fullText,
                                               AtomicReference<UsageInfo> upstreamUsage,
                                               AtomicReference<Long> channelId) {
        AtomicBoolean contentEmitted = new AtomicBoolean(false);
        return callWithFailover(call, new HashSet<>(), contentEmitted, channelId)
                .doOnNext(chunk -> {
                    if (chunk.hasContent()) {
                        fullText.append(chunk.content());
                    }
                    if (chunk.usage() != null) {
                        upstreamUsage.set(chunk.usage());
                    }
                });
    }

    private Flux<UpstreamChunk> callWithFailover(ChatCall call,
                                                 Set<Long> tried,
                                                 AtomicBoolean contentEmitted,
                                                 AtomicReference<Long> channelId) {
        return channelRouter.select(call.model(), tried)
                .switchIfEmpty(Mono.error(new BizException(ErrorCode.CHANNEL_UNAVAILABLE,
                        "无可用模型渠道：" + call.model())))
                .flatMapMany(channel -> {
                    log.info("route msg to channel id={} name={} provider={}",
                            channel.getId(), channel.getChannelName(), channel.getProvider());
                    channelId.set(channel.getId());
                    return llmClients.forChannel(channel)
                            .chat(channel, call.messages(), call.temperature())
                            .doOnNext(chunk -> {
                                if (chunk.hasContent()) {
                                    contentEmitted.set(true);
                                }
                            })
                            .onErrorResume(e -> onUpstreamError(call, channel, tried, contentEmitted, channelId, e));
                });
    }

    private Flux<UpstreamChunk> onUpstreamError(ChatCall call,
                                                com.prism.chat.entity.ModelChannel failed,
                                                Set<Long> tried,
                                                AtomicBoolean contentEmitted,
                                                AtomicReference<Long> channelId,
                                                Throwable e) {
        if (!isRetryableUpstreamFailure(e)) {
            return Flux.error(e); // 4xx 等我方请求问题：换渠道也无益
        }
        log.warn("upstream channel {} failed ({}: {}), failover. tried={}",
                failed.getId(), e.getClass().getSimpleName(), e.getMessage(), tried);
        // 连续失败计数 +1（TTL 5min，达到 3 次被路由摘除；计数本身失败不影响主链路）
        Mono<Void> markFail = channelRepository.recordFail(failed.getId())
                .onErrorResume(x -> Mono.empty());
        boolean canRetry = !contentEmitted.get() && tried.size() < MAX_RETRIES;
        if (!canRetry) {
            // 已吐过内容无法安全重放（会重复输出），或重试额度用尽 → 502
            return markFail.thenMany(Flux.error(new BizException(ErrorCode.UPSTREAM_ERROR,
                    "上游模型服务调用失败：" + e.getMessage(), e)));
        }
        Set<Long> next = new HashSet<>(tried);
        next.add(failed.getId());
        return markFail.thenMany(callWithFailover(call, next, contentEmitted, channelId));
    }

    /** 可触发故障转移的上游故障：5xx / 超时 / 连接失败（连接被重置、DNS、SSL 握手中断等） */
    static boolean isRetryableUpstreamFailure(Throwable e) {
        if (e instanceof WebClientResponseException wre) {
            return wre.getStatusCode().is5xxServerError();
        }
        return e instanceof WebClientRequestException            // 连接类失败
                || e instanceof java.util.concurrent.TimeoutException
                || e instanceof PrematureCloseException;
    }

    // ==================== 流式组装 ====================

    private Flux<String> upstreamStream(ChatCall call, Prepared p) {
        StringBuilder fullText = new StringBuilder();
        AtomicReference<UsageInfo> upstreamUsage = new AtomicReference<>();
        AtomicReference<Long> channelId = new AtomicReference<>();
        return invokeUpstream(call, fullText, upstreamUsage, channelId)
                .filter(UpstreamChunk::hasContent)
                .map(chunk -> chunkData(p.msgId(), call.model(), chunk.content()))
                .concatWith(Mono.fromSupplier(() ->
                        usageData(p.msgId(), resolveUsage(call, upstreamUsage.get(), fullText.toString()))))
                .concatWith(Mono.just("[DONE]"))
                .doOnComplete(() -> cacheService.put(call.model(), p.normalizedPrompt(),
                        p.lookup().embedding(), fullText.toString()))
                .doFinally(sig -> publishUsage(call, p, channelId.get(), upstreamUsage.get(),
                        fullText.toString(), false));
    }

    private Flux<String> replayStream(ChatCall call, Prepared p) {
        String text = p.lookup().response();
        return Flux.fromIterable(slice(text, REPLAY_SLICE))
                .delayElements(REPLAY_DELAY)
                .map(s -> chunkData(p.msgId(), call.model(), s))
                .concatWith(Mono.fromSupplier(() ->
                        usageData(p.msgId(), resolveUsage(call, null, text))))
                .concatWith(Mono.just("[DONE]"))
                .doFinally(sig -> publishUsage(call, p, null, null, text, true));
    }

    // ==================== 聚合（stream=false） ====================

    private Mono<Map<String, Object>> aggregateUpstream(ChatCall call, Prepared p) {
        StringBuilder fullText = new StringBuilder();
        AtomicReference<UsageInfo> upstreamUsage = new AtomicReference<>();
        AtomicReference<Long> channelId = new AtomicReference<>();
        return invokeUpstream(call, fullText, upstreamUsage, channelId)
                .then(Mono.fromSupplier(fullText::toString))
                .doOnNext(text -> cacheService.put(call.model(), p.normalizedPrompt(),
                        p.lookup().embedding(), text))
                .map(text -> completionJson(p.msgId(), call.model(), text,
                        resolveUsage(call, upstreamUsage.get(), text)))
                .doFinally(sig -> publishUsage(call, p, channelId.get(), upstreamUsage.get(),
                        fullText.toString(), false));
    }

    private Mono<Map<String, Object>> aggregateReplay(ChatCall call, Prepared p) {
        String text = p.lookup().response();
        return Mono.fromSupplier(() -> completionJson(p.msgId(), call.model(), text,
                        resolveUsage(call, null, text)))
                .doFinally(sig -> publishUsage(call, p, null, null, text, true));
    }

    // ==================== 用量事件（旁路） ====================

    private void publishUsage(ChatCall call, Prepared p, Long channelId,
                              UsageInfo upstream, String fullText, boolean cacheHit) {
        UsageInfo usage = resolveUsage(call, upstream, fullText);
        long latency = System.currentTimeMillis() - p.startedAt();
        UsageEvent event = UsageEvent.builder()
                .msgId(String.valueOf(p.msgId()))
                .appId(call.appId())
                .appKey(call.appKey())
                .model(call.model())
                .channelId(channelId)
                .promptTokens(usage.promptTokens())
                .completionTokens(usage.completionTokens())
                .totalTokens(usage.totalTokens())
                .latencyMs(latency)
                .cacheHit(cacheHit)
                .occurredAt(LocalDateTime.now().format(OCCURRED_AT))
                .build();
        // 旁路发布（fire-and-forget）：计量允许最终一致，发布失败只记日志不影响响应，
        // 偶发丢失由 billing 的每日对账任务兜底（CONTRACTS §6.3 reconcile）。
        usagePublisher.publish(event)
                .doOnError(e -> log.error("publish usage event failed, msgId={}", event.getMsgId(), e))
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }

    /** 优先上游真实 usage（OpenAI 兼容上游在流尾返回）；上游没给（如 mock）→ 本地近似估算 */
    private UsageInfo resolveUsage(ChatCall call, UsageInfo upstream, String completionText) {
        if (upstream != null) {
            return upstream;
        }
        String promptAll = call.messages().stream()
                .map(ChatMessage::getContent)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
        int prompt = TokenEstimator.estimate(promptAll);
        int completion = TokenEstimator.estimate(completionText);
        return new UsageInfo(prompt, completion, prompt + completion);
    }

    // ==================== OpenAI 格式组装 ====================

    private String chunkData(long msgId, String model, String content) {
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("content", content);
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", null);
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("id", "chatcmpl-" + msgId);
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", Instant.now().getEpochSecond());
        chunk.put("model", model);
        chunk.put("choices", List.of(choice));
        return writeJson(chunk);
    }

    private String usageData(long msgId, UsageInfo usage) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", "chatcmpl-" + msgId);
        payload.put("usage", usageMap(usage));
        return writeJson(payload);
    }

    private Map<String, Object> completionJson(long msgId, String model, String content, UsageInfo usage) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", content);
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", "stop");
        Map<String, Object> completion = new LinkedHashMap<>();
        completion.put("id", "chatcmpl-" + msgId);
        completion.put("object", "chat.completion");
        completion.put("created", Instant.now().getEpochSecond());
        completion.put("model", model);
        completion.put("choices", List.of(choice));
        completion.put("usage", usageMap(usage));
        return completion;
    }

    private Map<String, Object> usageMap(UsageInfo usage) {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("prompt_tokens", usage.promptTokens());
        u.put("completion_tokens", usage.completionTokens());
        u.put("total_tokens", usage.totalTokens());
        return u;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("serialize response payload failed", e);
        }
    }

    private static List<String> slice(String text, int size) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < text.length(); i += size) {
            out.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return out;
    }
}
