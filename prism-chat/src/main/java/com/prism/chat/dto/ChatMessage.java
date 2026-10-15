package com.prism.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OpenAI 兼容消息（role: system / user / assistant）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    private String role;
    private String content;
}
