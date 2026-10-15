package com.prism.common.constant;

/**
 * Redis Key 规范（全部带 prism: 前缀）
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    /** 应用信息 hash：field -> appId / userId / status / quota / rate，由 user 服务写入，网关只读 */
    public static final String APP_INFO = "prism:app:";

    public static String appInfo(String appKey) {
        return APP_INFO + appKey;
    }

    /** 令牌桶限流：hash {tokens, last_refill}，Lua 原子补充+扣减 */
    public static final String RATE_LIMIT = "prism:rl:";

    public static String rateLimit(String appKey) {
        return RATE_LIMIT + appKey;
    }

    /** 月度已用 token 计数器（billing 消费后累加，chat 预检读取） */
    public static final String QUOTA_USED = "prism:quota:used:";

    public static String quotaUsed(long appId, String yyyyMM) {
        return QUOTA_USED + appId + ":" + yyyyMM;
    }

    /** usage 消息幂等去重（billing 消费端 setnx） */
    public static final String USAGE_IDEM = "prism:idem:usage:";

    public static String usageIdem(String msgId) {
        return USAGE_IDEM + msgId;
    }

    /** 可用渠道列表缓存（JSON），TTL 60s，渠道变更时主动删除 */
    public static final String CHANNEL_CACHE = "prism:channel:cache";

    /** 渠道连续失败计数，达到阈值摘除 */
    public static final String CHANNEL_FAIL = "prism:channel:fail:";

    public static String channelFail(long channelId) {
        return CHANNEL_FAIL + channelId;
    }
}
