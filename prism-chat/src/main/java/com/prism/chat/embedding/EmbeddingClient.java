package com.prism.chat.embedding;

import reactor.core.publisher.Mono;

/**
 * embedding 抽象：语义缓存用它把 prompt 变成向量做余弦比较。
 * 返回 Flux/Mono 意义上的冷源，本地实现零开销，远程实现走 HTTP。
 */
public interface EmbeddingClient {

    /**
     * @param text 归一化后的 prompt
     * @return 向量（维度由实现决定；本地哈希实现为 512 维，已 L2 归一化）
     */
    Mono<float[]> embed(String text);
}
