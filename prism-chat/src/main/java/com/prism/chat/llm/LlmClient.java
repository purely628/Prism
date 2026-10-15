package com.prism.chat.llm;

import com.prism.chat.dto.ChatMessage;
import com.prism.chat.entity.ModelChannel;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 上游 LLM 客户端抽象。
 *
 * <p>实现约定：</p>
 * <ul>
 *   <li>上游 5xx / 超时 / 连接失败以 onError 信号抛出，由编排层（ChatOrchestrator）
 *       做渠道故障转移，对调用方透明；</li>
 *   <li>内容以增量 content 片段发出，真实 usage（若有）在流尾以 usage-only 片段发出。</li>
 * </ul>
 */
public interface LlmClient {

    /** 是否能处理该 base_url（mock:// 前缀 → MockLlmClient；http(s):// → OpenAiCompatClient） */
    boolean supports(String baseUrl);

    Flux<UpstreamChunk> chat(ModelChannel channel, List<ChatMessage> messages, Double temperature);
}
