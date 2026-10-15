package com.prism.kb.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.prism.kb.service.AgentClient;
import com.prism.common.result.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * RAG 能力代理：检索测试（同步 JSON）与知识库问答（SSE 流式透传）。
 *
 * <p>SSE 透传说明：MVC 下 StreamingResponseBody 用容器线程池执行，每连接占用一个线程——
 * 这是当前实现的取舍（实现简单、逐行 flush 语义清晰）；并发 RAG 会话规模大时应换
 * WebFlux 网关直连或异步 Servlet（Servlet 3.1 + 非阻塞 IO）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/kb")
@RequiredArgsConstructor
public class KbProxyController {

    private final AgentClient agentClient;

    /** 检索测试：返回 [{content,score,docName,chunkIndex}] */
    @PostMapping("/{kbId}/retrieve")
    public Result<JsonNode> retrieve(@PathVariable Long kbId, @Valid @RequestBody RetrieveRequest req) {
        int topK = req.getTopK() == null || req.getTopK() < 1 ? 4 : Math.min(req.getTopK(), 10);
        return Result.ok(agentClient.retrieve(kbId, req.getQuery(), topK));
    }

    /** RAG 对话：SSE 流式透传（event: chunk/citations/done/error，见契约 §6.6） */
    @PostMapping(value = "/{kbId}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public StreamingResponseBody chat(@PathVariable Long kbId, @Valid @RequestBody ChatRequest req) {
        return outputStream -> {
            try (var in = agentClient.chatStream(kbId, req.getQuestion(), req.getTopK());
                 var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputStream.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                }
            } catch (Exception e) {
                log.error("RAG 流式转发异常 kbId={}: {}", kbId, e.getMessage());
                // 已开始的 SSE 无法改状态码，发一个 error 事件让前端展示
                outputStream.write(("event: error\ndata: {\"message\":\"知识库服务暂不可用\"}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            }
        };
    }

    @Data
    public static class RetrieveRequest {
        @NotBlank(message = "查询内容不能为空")
        private String query;
        private Integer topK;
    }

    @Data
    public static class ChatRequest {
        @NotBlank(message = "问题不能为空")
        private String question;
        private Integer topK;
    }
}
