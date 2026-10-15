package com.prism.billing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.billing.entity.FailedMessage;
import com.prism.billing.mapper.FailedMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 失败消息兜底：Rabbit DLQ / Stream 毒消息原文落 t_failed_message。
 * best-effort：落库失败只记日志，不向上抛（不阻塞消费主流程）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FailedMessageService {

    private final FailedMessageMapper failedMessageMapper;
    private final ObjectMapper objectMapper;

    public void save(String payload, String error) {
        try {
            FailedMessage fm = new FailedMessage();
            fm.setMsgId(extractMsgId(payload));
            fm.setPayload(payload);
            fm.setError(error == null ? "" : error.substring(0, Math.min(error.length(), 500)));
            fm.setCreatedAt(LocalDateTime.now());
            failedMessageMapper.insert(fm);
        } catch (Exception e) {
            log.error("失败消息落库失败（best-effort 忽略）: {}", e.getMessage());
        }
    }

    private String extractMsgId(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            return node.path("msgId").asText("");
        } catch (Exception e) {
            return "";
        }
    }
}
