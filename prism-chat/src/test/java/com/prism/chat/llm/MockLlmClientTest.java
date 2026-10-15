package com.prism.chat.llm;

import com.prism.chat.dto.ChatMessage;
import com.prism.chat.entity.ModelChannel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mock 上游确定性：同 prompt 必同输出（语义缓存可命中的前提）、长度 120~250、
 * token 切片 4~8 字、Flux 两次订阅结果一致。
 */
class MockLlmClientTest {

    private final MockLlmClient client = new MockLlmClient();

    private static ModelChannel mockChannel() {
        ModelChannel c = new ModelChannel();
        c.setId(1L);
        c.setBaseUrl("mock://local");
        c.setModelName("demo-mock");
        return c;
    }

    @Test
    void samePromptProducesIdenticalAnswer() {
        String a = client.generate("如何设计一个高可用的网关");
        String b = client.generate("如何设计一个高可用的网关");
        assertThat(a).isEqualTo(b);
        assertThat(client.generate("另一个完全不同的问题")).isNotEqualTo(a);
    }

    @Test
    void answerLengthStaysIn120To250() {
        List<String> prompts = List.of(
                "", "hi", "知识库", "如何做知识库检索",
                "请详细解释一下大模型网关的语义缓存设计要点",
                "what is semantic cache", "1", "今天天气怎么样，适合出去玩吗");
        for (String p : prompts) {
            int len = client.generate(p).length();
            assertThat(len).as("prompt=%s", p).isBetween(120, 250);
        }
    }

    @Test
    void tokenizeChunksAre4To8CharsAndRejoinable() {
        String answer = client.generate("多轮对话上下文管理");
        List<String> tokens = MockLlmClient.tokenize(answer);
        assertThat(tokens).isNotEmpty();
        assertThat(String.join("", tokens)).isEqualTo(answer);
        for (int i = 0; i < tokens.size() - 1; i++) { // 末片允许不足 4
            assertThat(tokens.get(i).length()).isBetween(4, 8);
        }
    }

    @Test
    void chatFluxIsDeterministicAndEndsWithUsage() {
        List<ChatMessage> messages = List.of(new ChatMessage("user", "语义缓存命中策略"));
        List<UpstreamChunk> first = client.chat(mockChannel(), messages, null).collectList().block();
        List<UpstreamChunk> second = client.chat(mockChannel(), messages, null).collectList().block();

        assertThat(first).hasSameSizeAs(second);
        assertThat(first).containsExactlyElementsOf(second);
        assertThat(first.get(first.size() - 1).usage()).isNotNull();      // 流尾带 usage
        assertThat(first.get(first.size() - 1).hasContent()).isFalse();
        String joined = first.stream()
                .filter(UpstreamChunk::hasContent)
                .map(UpstreamChunk::content)
                .reduce("", String::concat);
        assertThat(joined).isEqualTo(client.generate("语义缓存命中策略"));
    }

    @Test
    void supportsOnlyMockScheme() {
        assertThat(client.supports("mock://local")).isTrue();
        assertThat(client.supports("https://api.openai.com")).isFalse();
        assertThat(client.supports(null)).isFalse();
    }
}
