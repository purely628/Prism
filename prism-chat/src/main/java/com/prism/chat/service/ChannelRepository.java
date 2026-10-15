package com.prism.chat.service;

import com.prism.chat.entity.ModelChannel;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 渠道存取抽象（Redis 缓存 + MySQL 回源 + 失败计数）。
 * 抽成接口是为了让 ChannelRouter 的单测可以完全脱离 Redis/DB（stub 实现）。
 */
public interface ChannelRepository {

    /** 取某 alias 的启用渠道列表（优先 Redis 缓存 prism:channel:cache，miss 回源 DB 并回填） */
    Mono<List<ModelChannel>> loadEnabledChannels(String alias);

    /** 渠道连续失败计数（prism:channel:fail:{id}，TTL 5 分钟，过期自动清零=半开恢复） */
    Mono<Long> failCount(long channelId);

    /** 记一次上游失败（INCR + EXPIRE 5min），达到阈值由 ChannelRouter 摘除 */
    Mono<Void> recordFail(long channelId);
}
