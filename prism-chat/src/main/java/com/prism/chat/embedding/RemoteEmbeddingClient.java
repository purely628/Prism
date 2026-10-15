package com.prism.chat.embedding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * 远程 embedding 实现：POST {remote-url}/embed，body {"text": ...}，返回 float[]。
 * 对接 agent-service（FastAPI）的 /embed 接口（CONTRACTS §6.5）。
 * 由 EmbeddingConfig 在配置了 prism.embed.remote-url 时装配。
 */
@Slf4j
public class RemoteEmbeddingClient implements EmbeddingClient {

    private final WebClient webClient;
    private final Duration timeout;

    public RemoteEmbeddingClient(WebClient.Builder builder, String baseUrl, Duration timeout) {
        this.webClient = builder.baseUrl(baseUrl).build();
        this.timeout = timeout;
    }

    @Override
    public Mono<float[]> embed(String text) {
        return webClient.post()
                .uri("/embed")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("text", text == null ? "" : text))
                .retrieve()
                .bodyToMono(float[].class)
                .timeout(timeout)
                .doOnError(e -> log.warn("remote embed failed: {}", e.getMessage()));
    }
}
