package com.prism.chat.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.chat.dto.ChatMessage;
import com.prism.chat.entity.ModelChannel;
import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容上游客户端：WebClient POST {base_url}/chat/completions。
 * 请求 model 用渠道的真实模型名（modelName），鉴权 Bearer {apiKey}；
 * connect timeout 10s（超时/5xx/连接失败以 onError 抛出，由编排层故障转移）。
 *
 * <p>上游 SSE 行（data: {...} / data: [DONE]）逐行解析：
 * 透传 choices[0].delta.content 片段，捕获流尾 usage；
 * 兼容非流式整体返回（choices[0].message.content 一次性片段）。</p>
 */
@Slf4j
@Component
public class OpenAiCompatClient implements LlmClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public OpenAiCompatClient(WebClient.Builder builder, ObjectMapper objectMapper) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000);
        this.webClient = builder
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String baseUrl) {
        return baseUrl != null && (baseUrl.startsWith("http://") || baseUrl.startsWith("https://"));
    }

    @Override
    public Flux<UpstreamChunk> chat(ModelChannel channel, List<ChatMessage> messages, Double temperature) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", channel.getModelName());
        body.put("messages", messages);
        body.put("stream", true);
        if (temperature != null) {
            body.put("temperature", temperature);
        }
        URI uri = URI.create(channel.getBaseUrl() + "/chat/completions");
        return webClient.post()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + (channel.getApiKey() == null ? "" : channel.getApiKey()))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .takeUntil("[DONE]"::equals)
                .mapNotNull(this::parseChunk);
    }

    /** 解析一行上游数据（容错：可能是 SSE data 负载，也可能带 data: 前缀的原始行） */
    private UpstreamChunk parseChunk(String raw) {
        String line = raw == null ? "" : raw.strip();
        if (line.startsWith("data:")) {
            line = line.substring(5).strip();
        }
        if (line.isEmpty() || "[DONE]".equals(line)) {
            return null;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(line);
        } catch (Exception e) {
            log.warn("skip unparsable upstream line: {}", abbreviate(line));
            return null;
        }
        String content = null;
        JsonNode deltaContent = node.path("choices").path(0).path("delta").path("content");
        if (deltaContent.isTextual()) {
            content = deltaContent.asText();
        }
        if (content == null) {
            // 兼容非流式整体 JSON 返回
            JsonNode messageContent = node.path("choices").path(0).path("message").path("content");
            if (messageContent.isTextual()) {
                content = messageContent.asText();
            }
        }
        UsageInfo usage = null;
        JsonNode u = node.path("usage");
        if (u.isObject() && u.hasNonNull("total_tokens")) {
            usage = new UsageInfo(
                    u.path("prompt_tokens").asInt(0),
                    u.path("completion_tokens").asInt(0),
                    u.path("total_tokens").asInt(0));
        }
        if (content == null && usage == null) {
            return null;
        }
        return new UpstreamChunk(content, usage);
    }

    private static String abbreviate(String s) {
        return s.length() <= 80 ? s : s.substring(0, 80) + "...";
    }
}
