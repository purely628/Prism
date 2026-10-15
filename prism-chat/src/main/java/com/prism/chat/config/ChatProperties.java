package com.prism.chat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * prism-chat 配置项（前缀 prism，见 application.yml / CONTRACTS.md §7）。
 */
@Data
@ConfigurationProperties(prefix = "prism")
public class ChatProperties {

    /** 雪花 ID 机器位（契约：chat=3） */
    private long workerId = 3;

    /** MQ 实现：stream = Redis Stream（默认，local 零依赖）；rabbit = RabbitMQ（compose） */
    private String mqMode = "stream";

    private SemanticCache semanticCache = new SemanticCache();

    private Embed embed = new Embed();

    @Data
    public static class SemanticCache {
        /** 语义缓存总开关 */
        private boolean enabled = true;
        /** 余弦相似度命中阈值 */
        private double threshold = 0.92;
    }

    @Data
    public static class Embed {
        /**
         * 远程 embedding 服务地址（如 agent-service 的 http://127.0.0.1:8200）。
         * 留空使用本地哈希实现（LocalHashEmbeddingClient）。
         */
        private String remoteUrl;
        private Duration timeout = Duration.ofSeconds(5);
    }
}
