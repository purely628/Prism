package com.prism.chat.util;

import com.prism.chat.dto.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * prompt 归一化：取最后一条 user 消息 + 空白合一 + 小写。
 */
class PromptNormalizerTest {

    @Test
    void takesLastUserMessageIgnoringCaseAndWhitespace() {
        List<ChatMessage> messages = List.of(
                new ChatMessage("system", "You are helpful."),
                new ChatMessage("user", "  What   is\n\nPrism? "),
                new ChatMessage("assistant", "Prism is a gateway."));
        assertThat(PromptNormalizer.lastUserNormalized(messages)).isEqualTo("what is prism?");
    }

    @Test
    void collapsesFullWidthSpacesToo() {
        assertThat(PromptNormalizer.normalize("　知识库　检索 ")).isEqualTo("知识库 检索");
    }

    @Test
    void nullAndEmptyAreSafe() {
        assertThat(PromptNormalizer.normalize(null)).isEmpty();
        assertThat(PromptNormalizer.lastUserNormalized(null)).isEmpty();
        assertThat(PromptNormalizer.lastUserNormalized(List.of())).isEmpty();
    }

    @Test
    void fallsBackToLastMessageWhenNoUser() {
        List<ChatMessage> messages = List.of(new ChatMessage("system", " Hello "));
        assertThat(PromptNormalizer.lastUserNormalized(messages)).isEqualTo("hello");
    }
}
