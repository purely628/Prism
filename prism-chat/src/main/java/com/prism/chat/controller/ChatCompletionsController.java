package com.prism.chat.controller;

import com.prism.chat.dto.ChatCall;
import com.prism.chat.dto.ChatMessage;
import com.prism.chat.dto.ChatRequest;
import com.prism.chat.error.OpenAiErrors;
import com.prism.chat.service.ChatOrchestrator;
import com.prism.common.exception.BizException;
import com.prism.common.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对外唯一接口：POST /v1/chat/completions（OpenAI 兼容，端口 8102）。
 *
 * <p>鉴权模型：网关在 /v1/** 鉴权通过后注入 X-App-Id / X-App-Key / X-App-Quota，
 * 本服务直接信任这些内网头；直连调试时需手工携带（缺头 → 401 invalid_api_key）。</p>
 *
 * <p>流式返回 Flux&lt;String&gt; + text/event-stream：WebFlux 会把每个元素写成一行 data:。
 * 首帧之前出错 → 异常上抛，全局异常处理器给出 429/502 等正确状态码；
 * 首帧之后出错 → 只能以 SSE error 数据事件收尾。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ChatCompletionsController {

    private final ChatOrchestrator orchestrator;

    @PostMapping("/v1/chat/completions")
    public Mono<ResponseEntity<?>> chat(@RequestBody ChatRequest request, ServerHttpRequest httpRequest) {
        ChatCall call = toCall(httpRequest, request);
        validate(request);
        if (call.stream()) {
            AtomicBoolean started = new AtomicBoolean(false);
            Flux<String> sse = orchestrator.stream(call)
                    .doOnNext(payload -> started.set(true))
                    .onErrorResume(e -> started.get()
                            ? Flux.just(OpenAiErrors.sseErrorPayload(e), "[DONE]")
                            : Flux.error(e));
            return Mono.just(ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(sse));
        }
        return orchestrator.aggregate(call)
                .map(json -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body((Object) json));
    }

    // ---------- 鉴权头解析（网关注入 / 直连手工） ----------

    private ChatCall toCall(ServerHttpRequest http, ChatRequest request) {
        HttpHeaders headers = http.getHeaders();
        String appIdRaw = headers.getFirst("X-App-Id");
        String quotaRaw = headers.getFirst("X-App-Quota");
        if (!StringUtils.hasText(appIdRaw) || !StringUtils.hasText(quotaRaw)) {
            throw new BizException(ErrorCode.API_KEY_INVALID,
                    "缺少应用身份头 X-App-Id / X-App-Quota：正常流量由网关注入，直连调试请手工携带");
        }
        long appId;
        long quota;
        try {
            appId = Long.parseLong(appIdRaw.trim());
            quota = Long.parseLong(quotaRaw.trim());
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.API_KEY_INVALID,
                    "应用身份头 X-App-Id / X-App-Quota 不是合法数字");
        }
        String appKey = firstNonBlank(
                headers.getFirst("X-App-Key"),
                bearerKey(headers),
                headers.getFirst("X-Api-Key"));
        return new ChatCall(appId, appKey, quota, request.getModel(),
                request.getMessages(), request.getTemperature(), request.streamEnabled());
    }

    private static String bearerKey(HttpHeaders headers) {
        String auth = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return auth.substring(7).trim();
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (StringUtils.hasText(v)) {
                return v;
            }
        }
        return "";
    }

    // ---------- 请求校验（400 OpenAI error 格式） ----------

    private void validate(ChatRequest request) {
        if (!StringUtils.hasText(request.getModel())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "model 不能为空");
        }
        List<ChatMessage> messages = request.getMessages();
        if (messages == null || messages.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "messages 不能为空");
        }
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (m == null || !StringUtils.hasText(m.getRole()) || m.getContent() == null) {
                throw new BizException(ErrorCode.PARAM_ERROR, "messages[" + i + "] 的 role/content 不能为空");
            }
        }
    }
}
