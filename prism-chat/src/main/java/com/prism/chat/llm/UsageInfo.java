package com.prism.chat.llm;

/**
 * 一次调用的 token 用量（优先上游真实 usage，否则 TokenEstimator 本地估算）。
 */
public record UsageInfo(int promptTokens, int completionTokens, int totalTokens) {
}
