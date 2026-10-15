package com.prism.kb.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Python agent-service 客户端（JDK HttpClient，无额外依赖）。
 * retrieve/purge 是同步小请求；chat 用 ofInputStream 流式转发 SSE。
 */
@Slf4j
@Component
public class AgentClient {

    /**
     * 强制 HTTP/1.1：JDK HttpClient 默认尝试 HTTP/2 协商，对 uvicorn(h11) 的 h2c 升级
     * 会导致 POST body 丢失（FastAPI 报 body missing/422）。上游是已知 HTTP/1.1 服务。
     */
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String baseUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentClient(@Value("${prism.agent-url:http://127.0.0.1:8200}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** 检索代理：POST {base}/internal/kb/{kbId}/retrieve → [{content,score,docName,chunkIndex}] */
    public JsonNode retrieve(long kbId, String query, int topK) {
        try {
            String body = objectMapper.createObjectNode()
                    .put("query", query).put("topK", topK).toString();
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/kb/" + kbId + "/retrieve"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("agent retrieve " + resp.statusCode() + ": " + resp.body());
            }
            return objectMapper.readTree(resp.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("检索服务不可达: " + e.getMessage(), e);
        }
    }

    /** 清向量（best-effort：Python 不在线也不影响元数据删除） */
    public void purgeQuietly(long kbId, Long docId) {
        try {
            String url = baseUrl + "/internal/kb/" + kbId + "/purge"
                    + (docId == null ? "" : "?docId=" + docId);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.warn("向量清理失败（best-effort）kbId={} docId={}: {}", kbId, docId, e.getMessage());
        }
    }

    /** 打开 RAG 对话 SSE 流，返回原始字节流（由调用方逐行写回响应并 flush） */
    public InputStream chatStream(long kbId, String question, Integer topK) throws IOException, InterruptedException {
        var node = objectMapper.createObjectNode().put("question", question).put("stream", true);
        if (topK != null) {
            node.put("topK", topK);
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/kb/" + kbId + "/chat"))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(node), StandardCharsets.UTF_8))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofInputStream()).body();
    }

    public Map<String, Object> health() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/healthz"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return Map.of("reachable", resp.statusCode() == 200);
        } catch (Exception e) {
            return Map.of("reachable", false, "error", String.valueOf(e.getMessage()));
        }
    }
}
