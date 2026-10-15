package com.prism.chat.embedding;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地哈希 embedding：确定性（同输入同输出）、L2 归一化、空输入安全、区分不同文本。
 */
class LocalHashEmbeddingClientTest {

    private final LocalHashEmbeddingClient client = new LocalHashEmbeddingClient();

    @Test
    void deterministicAcrossCalls() {
        float[] a = client.embed("知识库检索怎么做").block();
        float[] b = client.embed("知识库检索怎么做").block();
        assertThat(a).isNotNull();
        assertThat(b).isNotNull();
        assertThat(a).containsExactly(b); // 无随机源，必须逐位一致（语义缓存可命中的前提）
    }

    @Test
    void vectorIsL2Normalized() {
        float[] v = client.embed("今天天气怎么样？How is the weather today?").block();
        double squareSum = 0;
        for (float x : v) {
            squareSum += (double) x * x;
        }
        assertThat(Math.sqrt(squareSum)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
    }

    @Test
    void differentTextsProduceDifferentVectors() {
        float[] a = client.embed("机器学习入门").block();
        float[] b = client.embed("今天晚饭吃什么").block();
        assertThat(Arrays.equals(a, b)).isFalse();
    }

    @Test
    void emptyInputGivesZeroVectorWithoutNaN() {
        float[] v = client.embed("").block();
        assertThat(v).hasSize(LocalHashEmbeddingClient.DIM);
        assertThat(v).containsOnly(0.0f);
        float[] nul = client.embed(null).block();
        assertThat(nul).containsOnly(0.0f);
    }

    @Test
    void bucketIsStableAndBounded() {
        assertThat(LocalHashEmbeddingClient.bucket("知识"))
                .isEqualTo(LocalHashEmbeddingClient.bucket("知识"))
                .isBetween(0, LocalHashEmbeddingClient.DIM - 1);
    }
}
