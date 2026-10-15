package com.prism.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 网关 Bean：限流 Lua 脚本（classpath 加载为 DefaultRedisScript）+ 用户服务回源 WebClient
 */
@Configuration
public class GatewayBeansConfig {

    @Bean
    public RedisScript<Long> rateLimitScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("ratelimit.lua")));
        script.setResultType(Long.class);
        return script;
    }

    @Bean
    public WebClient userWebClient(WebClient.Builder builder,
                                    @Value("${prism.user-url}") String userUrl) {
        return builder.baseUrl(userUrl).build();
    }
}
