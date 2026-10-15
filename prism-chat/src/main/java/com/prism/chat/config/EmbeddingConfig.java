package com.prism.chat.config;

import com.prism.chat.embedding.EmbeddingClient;
import com.prism.chat.embedding.LocalHashEmbeddingClient;
import com.prism.chat.embedding.RemoteEmbeddingClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Embedding 客户端装配：
 * 配置了 prism.embed.remote-url → RemoteEmbeddingClient（真实向量服务）；
 * 否则退回 LocalHashEmbeddingClient（纯 Java 离线哈希，默认）。
 *
 * <p>注意：@Bean 方法按声明顺序求值，remote 在前、local 的
 * {@link ConditionalOnMissingBean} 才能在 remote 生效时正确退位。</p>
 */
@Configuration
public class EmbeddingConfig {

    @Bean
    @ConditionalOnProperty("prism.embed.remote-url")
    public EmbeddingClient remoteEmbeddingClient(ChatProperties props, WebClient.Builder builder) {
        String url = props.getEmbed().getRemoteUrl();
        if (!StringUtils.hasText(url)) {
            // 防御：compose 下环境变量默认空串时条件仍视为"已配置"，这里退回本地实现
            return new LocalHashEmbeddingClient();
        }
        return new RemoteEmbeddingClient(builder, url, props.getEmbed().getTimeout());
    }

    @Bean
    @ConditionalOnMissingBean(EmbeddingClient.class)
    public EmbeddingClient localHashEmbeddingClient() {
        return new LocalHashEmbeddingClient();
    }
}
