package com.prism.chat.util;

import com.prism.chat.dto.ChatMessage;

import java.util.List;
import java.util.Locale;

/**
 * 语义缓存的 prompt 归一化：
 * 取最后一条 user 消息内容，去首尾空白、连续空白（含全角空格）合一、转小写（英文）。
 *
 * <p>目的：让仅大小写 / 空白差异的输入命中同一条缓存。
 * 不做同义词、停用词处理——语义层面的相似交给 embedding 余弦判断。</p>
 */
public final class PromptNormalizer {

    private PromptNormalizer() {
    }

    /** 取最后一条 user 消息并归一化；无 user 消息时兜底用最后一条（演示容错） */
    public static String lastUserNormalized(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        String content = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if (m != null && "user".equals(m.getRole())) {
                content = m.getContent();
                break;
            }
        }
        if (content == null) {
            ChatMessage last = messages.get(messages.size() - 1);
            content = last == null ? null : last.getContent();
        }
        return normalize(content);
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return raw.strip()
                .replaceAll("[\\s\\u3000]+", " ")
                .toLowerCase(Locale.ROOT);
    }
}
