package com.prism.chat.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.prism.chat.config.ChatProperties;
import com.prism.chat.embedding.EmbeddingClient;
import com.prism.chat.util.CosineSimilarity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Map;

/**
 * 语义缓存：Caffeine 进程内缓存（maximumSize 256、写后 1 小时过期），
 * key = model + "|" + md5(归一化 prompt)，entry 含原始 prompt、embedding、完整回答。
 *
 * <p>查询不是精确匹配：取该 model 的全部 entry 与查询 embedding 算余弦相似度，
 * 最大值 ≥ 阈值（默认 0.92）即命中；命中后由编排层以 6 字符/30ms 节奏回放，
 * 并把 usage 事件标记 cacheHit=true。embedding 故障时降级为未命中（缓存是优化，不是依赖）。</p>
 */
@Slf4j
@Service
public class SemanticCacheService {

    public record CacheEntry(String prompt, float[] embedding, String response) {
    }

    /**
     * 查询结果。hit=false 时 embedding 仍带回（供未命中后写入缓存复用，避免二次计算）。
     */
    public record CacheLookup(boolean hit, float[] embedding, String response) {
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final EmbeddingClient embeddingClient;
    private final boolean enabled;
    private final double threshold;

    private final Cache<String, CacheEntry> cache = Caffeine.newBuilder()
            .maximumSize(256)
            .expireAfterWrite(Duration.ofHours(1))
            .build();

    @Autowired
    public SemanticCacheService(EmbeddingClient embeddingClient, ChatProperties props) {
        this(embeddingClient, props.getSemanticCache().isEnabled(), props.getSemanticCache().getThreshold());
    }

    /** 单测直用（不依赖 Spring 配置绑定） */
    public SemanticCacheService(EmbeddingClient embeddingClient, boolean enabled, double threshold) {
        this.embeddingClient = embeddingClient;
        this.enabled = enabled;
        this.threshold = threshold;
    }

    public Mono<CacheLookup> lookup(String model, String normalizedPrompt) {
        if (!enabled || !StringUtils.hasText(normalizedPrompt)) {
            return Mono.just(new CacheLookup(false, new float[0], null));
        }
        return embeddingClient.embed(normalizedPrompt)
                .map(query -> {
                    String prefix = model + "|";
                    CacheEntry best = null;
                    double bestScore = -1.0;
                    for (Map.Entry<String, CacheEntry> e : cache.asMap().entrySet()) {
                        if (!e.getKey().startsWith(prefix)) {
                            continue;
                        }
                        double score = CosineSimilarity.similarity(query, e.getValue().embedding());
                        if (score > bestScore) {
                            bestScore = score;
                            best = e.getValue();
                        }
                    }
                    if (best != null && bestScore >= threshold) {
                        log.info("semantic cache HIT: model={} score={} threshold={}", model, bestScore, threshold);
                        return new CacheLookup(true, query, best.response());
                    }
                    return new CacheLookup(false, query, null);
                })
                .onErrorResume(e -> {
                    log.warn("embedding lookup failed, degrade to cache miss: {}", e.getMessage());
                    return Mono.just(new CacheLookup(false, new float[0], null));
                });
    }

    public void put(String model, String normalizedPrompt, float[] embedding, String response) {
        if (!enabled || !StringUtils.hasText(normalizedPrompt)
                || embedding == null || embedding.length == 0
                || !StringUtils.hasText(response)) {
            return;
        }
        cache.put(cacheKey(model, normalizedPrompt), new CacheEntry(normalizedPrompt, embedding, response));
    }

    static String cacheKey(String model, String normalizedPrompt) {
        return model + "|" + md5Hex(normalizedPrompt);
    }

    public static String md5Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(HEX[(b >> 4) & 0xf]).append(HEX[b & 0xf]);
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }
}
