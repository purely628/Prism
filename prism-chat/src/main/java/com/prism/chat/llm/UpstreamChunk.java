package com.prism.chat.llm;

/**
 * 上游返回的流式片段：content 为增量文本（usage-only 片段时为 null），
 * usage 为上游真实用量（通常只在流尾出现一次，可能缺失）。
 */
public record UpstreamChunk(String content, UsageInfo usage) {

    public static UpstreamChunk content(String content) {
        return new UpstreamChunk(content, null);
    }

    public static UpstreamChunk usageOnly(UsageInfo usage) {
        return new UpstreamChunk(null, usage);
    }

    public boolean hasContent() {
        return content != null && !content.isEmpty();
    }
}
