package com.prism.chat.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 余弦相似度边界：同向=1、正交=0、反向=-1、零向量/维度不一致安全返回 0。
 */
class CosineSimilarityTest {

    @Test
    void identicalVectorsAreOne() {
        float[] v = {1f, 2f, 3f, 4f};
        assertThat(CosineSimilarity.similarity(v, v.clone())).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void orthogonalVectorsAreZero() {
        assertThat(CosineSimilarity.similarity(new float[]{1f, 0f}, new float[]{0f, 1f})).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void oppositeVectorsAreMinusOne() {
        assertThat(CosineSimilarity.similarity(new float[]{2f, 0f}, new float[]{-3f, 0f})).isCloseTo(-1.0, within(1e-9));
    }

    @Test
    void degenerateInputsReturnZeroInsteadOfNaN() {
        assertThat(CosineSimilarity.similarity(null, new float[]{1f})).isZero();
        assertThat(CosineSimilarity.similarity(new float[0], new float[0])).isZero();
        assertThat(CosineSimilarity.similarity(new float[]{1f, 2f}, new float[]{1f})).isZero(); // 维度不一致
        assertThat(CosineSimilarity.similarity(new float[]{0f, 0f}, new float[]{1f, 0f})).isZero(); // 零向量
        assertThat(CosineSimilarity.similarity(new float[512], new float[512])).isZero(); // 本地 embedding 空输入场景
    }

    @Test
    void pythagoreanTripleIsExactHalf() {
        // (4,3,5) 勾股数：cos = 4/(1*5) = 0.8 可精确表示，供阈值边界测试复用
        float[] a = {1f, 0f, 0f};
        float[] b = {4f, 0f, 3f};
        assertThat(CosineSimilarity.similarity(a, b)).isCloseTo(0.8, within(1e-12));
    }
}
