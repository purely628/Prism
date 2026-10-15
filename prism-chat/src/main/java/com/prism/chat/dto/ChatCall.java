package com.prism.chat.dto;

import java.util.List;

/**
 * 一次推理调用的上下文快照：网关注入的应用身份头 + 请求体合并后的不可变值对象。
 *
 * @param appId       应用 ID（X-App-Id）
 * @param appKey      应用 Key（X-App-Key，兜底取 Authorization Bearer / X-Api-Key，仅用于用量事件溯源）
 * @param quota       月配额（X-App-Quota）
 * @param model       对外模型名（alias）
 * @param messages    消息列表
 * @param temperature 可选采样温度
 * @param stream      是否流式
 */
public record ChatCall(long appId, String appKey, long quota, String model,
                       List<ChatMessage> messages, Double temperature, boolean stream) {
}
