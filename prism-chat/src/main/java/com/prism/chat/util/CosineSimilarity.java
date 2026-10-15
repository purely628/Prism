package com.prism.chat.util;

/**
 * 余弦相似度（语义缓存命中判定用）。
 * 非法输入（null / 维度不一致 / 零向量）一律返回 0：零向量没有方向，视为"不相似"，
 * 避免 NaN 污染阈值比较。
 */
public final class CosineSimilarity {

    private CosineSimilarity() {
    }

    public static double similarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            double x = a[i];
            double y = b[i];
            dot += x * y;
            normA += x * x;
            normB += y * y;
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
