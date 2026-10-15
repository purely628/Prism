package com.prism.chat.dto;

import lombok.Data;

import java.util.List;

/**
 * /v1/chat/completions 请求体（OpenAI 兼容子集）。
 * stream 缺省按 true 处理（本平台演示默认流式；与 OpenAI 官方默认 false 不同，见 CONTRACTS §6.2）。
 */
@Data
public class ChatRequest {

    /** 对外模型名（= 渠道表 alias） */
    private String model;

    private List<ChatMessage> messages;

    private Boolean stream;

    private Double temperature;

    public boolean streamEnabled() {
        return stream == null || stream;
    }
}
