-- ============================================================
-- Prism 网关令牌桶限流（原子：补充令牌 + 扣减）
-- KEYS[1] = prism:rl:{appKey}          hash: {tokens, lastTs}
-- ARGV[1] = rate       每秒补充令牌速率（= t_app.rate_limit）
-- ARGV[2] = capacity   桶容量（= rate * 2）
-- ARGV[3] = now        当前毫秒时间戳（由网关传入，避免多机时钟依赖 Redis TIME）
-- ARGV[4] = requested  本次申请的令牌数（当前固定为 1）
-- 返回：1 = 放行；0 = 令牌不足，限流
-- ============================================================

-- 1. 读取桶状态；首次请求（hash 不存在）时 HGET 返回 false，初始化为满桶 + 当前时间
local tokens = tonumber(redis.call('HGET', KEYS[1], 'tokens') or ARGV[2])
local lastTs = tonumber(redis.call('HGET', KEYS[1], 'lastTs') or ARGV[3])

-- 2. 按距上次补充的流逝毫秒数补充令牌：refill = 向下取整(elapsed/1000 * rate)，封顶 capacity
local elapsed = math.max(0, tonumber(ARGV[3]) - lastTs)
local refill = math.floor(elapsed / 1000 * tonumber(ARGV[1]))
if refill > 0 then
    tokens = math.min(tonumber(ARGV[2]), tokens + refill)
end

-- 3. 尝试扣减本次申请的令牌数：足够则扣减放行，不足则拒绝（不产生负数）
local allowed = 0
if tokens >= tonumber(ARGV[4]) then
    tokens = tokens - tonumber(ARGV[4])
    allowed = 1
end

-- 4. 写回桶状态（tokens + 本次请求时间戳），无 TTL：桶随 appKey 常驻
redis.call('HSET', KEYS[1], 'tokens', tokens, 'lastTs', ARGV[3])

-- 5. 返回放行标记
return allowed
