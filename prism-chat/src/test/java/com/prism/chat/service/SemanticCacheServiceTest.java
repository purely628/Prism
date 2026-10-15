package com.prism.chat.service;

import com.prism.chat.embedding.EmbeddingClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 语义缓存：命中 / 不命中 / 阈值边界（≥ 命中、< 不命中）/ 模型隔离 / 总开关。
 * EmbeddingClient 用 stub：prompt → 可控向量（勾股数构造出精确 0.8 相似度）。
 */
class SemanticCacheServiceTest {

    /** stub：按 prompt 精确给向量，未注册的 prompt 给正交零向量 */
    private static class StubEmbedding implements EmbeddingClient {
        final Map<String, float[]> vectors = new HashMap<>();

        @Override
        public Mono<float[]> embed(String text) {
            return Mono.just(vectors.getOrDefault(text, new float[512]));
        }
    }

    private static float[] vec(float... values) {
        float[] full = new float[512];
        for (int i = 0; i < values.length; i++) {
            full[i] = values[i];
        }
        return full;
    }

    @Test
    void identicalPromptHits() {
        StubEmbedding stub = new StubEmbedding();
        stub.vectors.put("知识库检索", vec(1f, 0f, 0f));
        SemanticCacheService cache = new SemanticCacheService(stub, true, 0.92);
        cache.put("prism-demo", "知识库检索", vec(1f, 0f, 0f), " cached answer ");

        SemanticCacheService.CacheLookup hit = cache.lookup("prism-demo", "知识库检索").block();
        assertThat(hit.hit()).isTrue();
        assertThat(hit.response()).isEqualTo(" cached answer ");
    }

    @Test
    void dissimilarPromptMisses() {
        StubEmbedding stub = new StubEmbedding();
        stub.vectors.put("今天晚饭吃什么", vec(0f, 1f, 0f));      // 与缓存向量正交
        SemanticCacheService cache = new SemanticCacheService(stub, true, 0.92);
        cache.put("prism-demo", "知识库检索", vec(1f, 0f, 0f), "ans");

        SemanticCacheService.CacheLookup miss = cache.lookup("prism-demo", "今天晚饭吃什么").block();
        assertThat(miss.hit()).isFalse();
        assertThat(miss.response()).isNull();
    }

    @Test
    void thresholdBoundaryExactScoreHits() {
        // (4,3,5) 勾股数：cached [1,0,0] vs query [4,0,3] → cos 恰为 0.8
        StubEmbedding stub = new StubEmbedding();
        stub.vectors.put("query", vec(4f, 0f, 3f));
        SemanticCacheService cache = new SemanticCacheService(stub, true, 0.80);
        cache.put("m", "cached", vec(1f, 0f, 0f), "ans");

        assertThat(cache.lookup("m", "query").block().hit()).isTrue();   // 0.8 >= 0.8 命中
    }

    @Test
    void justBelowThresholdMisses() {
        StubEmbedding stub = new StubEmbedding();
        stub.vectors.put("query", vec(3f, 2f, 6f)); // cos = 3/7 ≈ 0.4286
        SemanticCacheService cache = new SemanticCacheService(stub, true, 0.43);
        cache.put("m", "cached", vec(1f, 0f, 0f), "ans");

        assertThat(cache.lookup("m", "query").block().hit()).isFalse();  // 0.4286 < 0.43
    }

    @Test
    void entriesAreIsolatedByModel() {
        StubEmbedding stub = new StubEmbedding();
        stub.vectors.put("知识库检索", vec(1f, 0f, 0f));
        SemanticCacheService cache = new SemanticCacheService(stub, true, 0.92);
        cache.put("model-a", "知识库检索", vec(1f, 0f, 0f), "ans");

        // 同 prompt、同向量，但 model 不同 → 不能命中
        assertThat(cache.lookup("model-b", "知识库检索").block().hit()).isFalse();
        assertThat(cache.lookup("model-a", "知识库检索").block().hit()).isTrue();
    }

    @Test
    void disabledCacheNeverHitsAndPutIsNoOp() {
        StubEmbedding stub = new StubEmbedding();
        stub.vectors.put("p", vec(1f, 0f, 0f));
        SemanticCacheService cache = new SemanticCacheService(stub, false, 0.92);
        cache.put("m", "p", vec(1f, 0f, 0f), "ans");

        assertThat(cache.lookup("m", "p").block().hit()).isFalse();
    }
}
