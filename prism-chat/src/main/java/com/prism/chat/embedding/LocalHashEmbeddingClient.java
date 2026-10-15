package com.prism.chat.embedding;

import reactor.core.publisher.Mono;

/**
 * 纯 Java 离线 embedding（默认实现，零网络零依赖）：
 * 把文本的中英文 char-bigram 哈希到 512 维桶做 TF 统计，再 L2 归一化。
 *
 * <p><b>能力边界</b>：只能捕捉字面 n-gram 重叠，没有真正的语义泛化
 * （"计算机"和"电脑"不会相似）。它换来的是：离线可跑、确定性、微秒级耗时，
 * 适合本地联调与语义缓存的链路演示。
 * <b>生产应替换为真实 embedding 服务</b>（配置 prism.embed.remote-url 切到
 * RemoteEmbeddingClient / agent-service 的 /embed）。</p>
 */
public class LocalHashEmbeddingClient implements EmbeddingClient {

    public static final int DIM = 512;

    @Override
    public Mono<float[]> embed(String text) {
        return Mono.fromSupplier(() -> embedSync(text));
    }

    float[] embedSync(String text) {
        float[] vector = new float[DIM];
        if (text == null || text.isEmpty()) {
            return vector;
        }
        String s = text.strip();
        int n = s.length();
        if (n == 1) {
            vector[bucket(s)] = 1.0f;
        } else {
            for (int i = 0; i + 1 < n; i++) {
                vector[bucket(s.substring(i, i + 2))] += 1.0f;
            }
        }
        // L2 归一化：余弦只关心方向
        double squareSum = 0.0;
        for (float v : vector) {
            squareSum += v * v;
        }
        if (squareSum <= 0.0) {
            return vector;
        }
        double inverse = 1.0 / Math.sqrt(squareSum);
        for (int i = 0; i < DIM; i++) {
            vector[i] = (float) (vector[i] * inverse);
        }
        return vector;
    }

    /** bigram → [0,512) 桶：对 String.hashCode 做雪崩扰动，减少聚簇 */
    static int bucket(String gram) {
        int h = gram.hashCode();
        h ^= (h >>> 16);
        h *= 0x7feb352d;
        h ^= (h >>> 15);
        return (h & 0x7fffffff) % DIM;
    }
}
