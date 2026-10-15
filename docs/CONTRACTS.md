# Prism 接口与模块契约（单一事实来源）

> 所有模块开发前必读。修改本文件需由集成者统一进行。

## 1. 项目定位

Prism —— LLM 能力开放平台（AI 中台网关）。对外提供 **OpenAI 兼容**的 `/v1/chat/completions` 接口（客户端只需换 base_url + API Key 即可接入）；内部实现多模型路由、流量治理、语义缓存、Token 计量计费与配额管控；并挂载 LangChain RAG 知识库与 ReAct Agent 能力（Python 服务）。Java 微服务为主体，Python 做 AI 编排。

## 2. 模块与端口

| 模块 | 目录 | 端口 | 技术栈 | 职责 |
|---|---|---|---|---|
| 网关 | prism-gateway | 8080 | Spring Cloud Gateway (WebFlux) | API Key/JWT 鉴权、Redis+Lua 令牌桶限流、路由转发、SSE 透传 |
| 用户/应用 | prism-user | 8101 | Boot MVC + MyBatis-Plus | 登录(JWT)、用户、应用与 API Key、模型渠道管理、种子数据 |
| 模型服务 | prism-chat | 8102 | Boot **WebFlux** + MyBatis-Plus(只读渠道) | OpenAI 兼容推理入口：配额预检、语义缓存、加权路由+故障转移、SSE 流式透传、用量事件发布 |
| 计量计费 | prism-billing | 8103 | Boot MVC + MyBatis-Plus | MQ 消费(幂等/攒批入库)、Redis 配额累加、对账、月账单、用量查询接口 |
| 知识库 | prism-kb | 8104 | Boot MVC + MyBatis-Plus | 知识库/文档 CRUD、文件上传、解析任务编排(local:HTTP / compose:MQ)、代理 Python 检索 |
| Agent 服务 | agent-service | 8200 | Python 3.12 + FastAPI + LangChain | 文档解析入库(切分/embedding/Chroma)、RAG 问答(SSE+引用溯源)、ReAct Agent |
| 前端 | frontend | dev 5173 | Vue3 + Vite + Pinia + Element Plus + ECharts | 控制台 |

- 公共模块 `prism-common`：Result/ErrorCode/BizException/PageResult/JwtUtil/SnowFlakeId/RedisKeys/MqConstants/UsageEvent/GlobalExceptionHandler（MVC 用）。
- groupId `com.prism`，包名前缀 `com.prism.<module>`，JDK 17（release 编译），Spring Boot 3.4.1 / Spring Cloud 2024.0.0 / Spring Cloud Alibaba 2023.0.3.2 / MyBatis-Plus 3.5.10.1。

## 3. 数据库

MySQL 库 `prism`，建表脚本 `sql/init.sql`（已定稿，勿改表名/字段名）。表：t_user / t_app / t_model_channel / t_usage_record / t_bill / t_reconcile_diff / t_failed_message / t_kb / t_document / t_doc_chunk。

本地连接：`127.0.0.1:3306 root/1234`。compose 中按环境变量覆盖。

## 4. Redis Key（见 prism-common RedisKeys）

| Key | 类型 | 写方 | 读方 | 说明 |
|---|---|---|---|---|
| `prism:app:{appKey}` | hash(appId,userId,status,quota,rate) | user(创建/重置/禁用/改配额时) | gateway | gateway miss 时回调 user `/internal/apps/bykey` 回填 |
| `prism:rl:{appKey}` | hash(tokens,lastTs) | gateway Lua | gateway | 令牌桶，rate=app.rate，capacity=rate*2 |
| `prism:quota:used:{appId}:{yyyyMM}` | string(int) | billing(INCRBY) | chat(预检) | 月度已用 token |
| `prism:idem:usage:{msgId}` | string | billing SETNX(TTL 48h) | billing | 用量消息幂等 |
| `prism:channel:cache` | string(JSON数组) | user(变更时DEL) | chat(读,TTL 60s) | 启用状态渠道列表 |
| `prism:channel:fail:{channelId}` | string(int) | chat | chat | 连续失败≥3摘除，TTL 5min |

## 5. MQ（MqConstants）

- usage 事件：direct exchange `prism.usage.direct`，queue `prism.usage.record`，routing key `usage.record`；DLQ `prism.usage.record.dlq`。
- kb 任务：direct exchange `prism.kb.direct`，queue `prism.kb.task`，routing key `kb.task`，消息体 `{"docId":123}`。
- **双实现约定**：定义接口 `UsageEventPublisher`（chat 发布）、`UsageConsumer`（billing 消费）、`KbTaskDispatcher`（kb 派发）、Python 侧消费 kb 任务。提供两套实现，`@ConditionalOnProperty(name="prism.mq.mode", havingValue="rabbit")` 为 RabbitMQ 实现（RabbitTemplate/@RabbitListener/aio-pika）；默认 `stream` 实现走 **Redis Stream**：
  - usage 流：key `prism:stream:usage`，消费组 `billing`；kb 任务流：key `prism:stream:kb`，消费组 `agent-service`。
  - 生产 `XADD`（MAXLEN ~100000）；消费 `XREADGROUP ... >` 阻塞读 + 处理成功 `XACK`；启动时对 pending 中 idle>5min 的消息 `XPENDING`+`XCLAIM` 认领重投（简单故障恢复，文档说明生产可用 XAUTOCLAIM，本机 Redis 5.0 兼容）。
  - Python（redis-py）在 `MQ_MODE=stream` 时同样以消费组读 `prism:stream:kb`。
- 网关在 `/v1/**` 鉴权通过后为下游附加请求头：`X-App-Id` / `X-App-Key` / `X-App-Quota`（月配额）；`/api/**` JWT 校验通过后附加 `X-User-Id` / `X-Username`。下游直接信任这些内网头。

## 6. HTTP 接口契约

统一返回 `Result<T>` = `{"code":0,"message":"ok","data":...}`，非 0 为错误（ErrorCode）。`/api/**` 需 JWT（网关校验后透传 `X-User-Id`/`X-Username` 头）；`/v1/**` 需 `Authorization: Bearer {appKey}` 或 `X-Api-Key: {appKey}`；`/internal/**` 仅内网互调（网关拒绝外部 `/internal`）。

### 6.1 user 服务（8101）
- `POST /api/auth/login` {username,password} → {token, nickname} （无需JWT）
- `GET /api/auth/me` → {id,username,nickname,role}
- `GET /api/apps` → 应用列表(含月已用token：Redis 读 `prism:quota:used`)
- `POST /api/apps` {appName,monthlyQuota,rateLimit} → {id,appKey,...}（appKey=`sk-prism-`+32hex，同时写 Redis app hash）
- `POST /api/apps/{id}/resetKey` → 新 appKey（同步 Redis：删旧写新）
- `POST /api/apps/{id}/status` {status:0|1}（禁用时删 Redis hash）
- `POST /api/apps/{id}/quota` {monthlyQuota}（同步 Redis hash quota 字段 + DB）
- `DELETE /api/apps/{id}`
- 渠道（ADMIN）：`GET/POST /api/channels`、`PUT /api/channels/{id}`、`POST /api/channels/{id}/status` {status}（变更后 DEL `prism:channel:cache`）、`DELETE /api/channels/{id}`
- `GET /internal/apps/bykey?appKey=` → app hash 字段（gateway 回源用，无JWT）

### 6.2 chat 服务（8102）—— OpenAI 兼容
- `POST /v1/chat/completions`，body：`{"model":"prism-demo","messages":[{"role":"user","content":"..."}],"stream":true}`
- 响应（stream=true）：SSE，`data: {"id":"chatcmpl-x","model":"prism-demo","choices":[{"delta":{"content":"token"}}]}`，末尾 `data: {"usage":{...}}` + `data: [DONE]`
- 响应（stream=false）：OpenAI 单 JSON 格式（choices[0].message.content + usage）
- 错误：429 `{"error":{"message":"...","type":"rate_limit_error"}}`、配额 `quota_exceeded`、401 `invalid_api_key`（**注意：/v1 错误格式用 OpenAI 风格 error 对象，不用 Result**）

### 6.3 billing 服务（8103）
- `GET /api/usage/summary?days=7` → {totalTokens,totalCalls,cacheHitRate,daily:[{day,tokens,calls}],byModel:[{model,tokens}],topApps:[{appId,appName,tokens}]}
- `GET /api/usage/page?appId=&pageNum=1&pageSize=20` → PageResult<流水明细>
- `GET /api/usage/quota?appId=` → {monthlyQuota,used,month}
- `POST /internal/usage/reconcile` → 手动触发对账 {diffCount}（定时任务每日 02:30 也跑）
- `POST /internal/bills/generate` {month:"yyyy-MM"} → 手动出账（月度定时任务每月 1 号 03:00 自动跑，演示用此接口随时出）
- 账单：`GET /api/bills?month=yyyy-MM` → 列表；每月 1 号 03:00 定时出账（单价：prompt 0.002元/1k tokens、completion 0.004元/1k，仅演示）

### 6.4 kb 服务（8104）
- `GET/POST /api/kb`、`PUT /api/kb/{id}`、`DELETE /api/kb/{id}`（删除时同步调 Python `/internal/kb/{kbId}/purge` 清 Chroma）
- `GET /api/kb/{kbId}/documents` → 文档列表(状态/块数)
- `POST /api/kb/{kbId}/documents` multipart(file) → {docId}（存本地 `data/kb/{kbId}/{docId}/{原名}`，插 PENDING，派发解析任务）
- `DELETE /api/kb/{kbId}/documents/{docId}`（local:HTTP / compose:MQ 由 Python 清向量）
- `POST /api/kb/{kbId}/retrieve` {query,topK} → 代理 Python，[{content,score,docName,chunkIndex}]
- `POST /api/kb/{kbId}/chat` {question,stream=true} → 代理 Python SSE
- `GET /internal/kbs/{kbId}` → {chunkSize,chunkOverlap,topK}（Python 解析时读切分参数）
- `GET /internal/documents/{docId}/file` → 文件流（Python 下载）
- `POST /internal/documents/{docId}/status` {status,chunkCount,error,chunks:[{chunkIndex,content,tokenCount}]} → Python 回调：落 t_doc_chunk、更新 t_document（重试场景先删旧 chunk 再插，幂等）

### 6.5 agent 服务（Python, 8200）
- `POST /api/agent/run` {goal, stream=true} → SSE
- `GET /healthz`
- 内部：`/internal/kb/{kbId}/purge`、`/internal/kb/{kbId}/retrieve`、`/internal/parse`(mq 模式)、`/embed`(可选，给 chat 语义缓存用，返回 float[])

### 6.6 SSE 事件协议（RAG/Agent，非 OpenAI 格式）
- RAG chat：`event: chunk` data {"delta":".."}；`event: citations` data [{docName,chunkIndex,score,snippet}]；`event: done` data {"promptTokens":n,"completionTokens":n}；`event: error` data {"message":".."}
- Agent run：`event: thought`/`event: action` data {"tool":"kb_search","input":".."} /`event: observation` data {"output":".."} /`event: answer` data {"delta":".."} /`event: done` /`event: error`

## 7. 环境变量 / 配置

- 两个 profile：`local`（默认，无 Nacos/RabbitMQ：路由直连 localhost、MQ 用 inmemory）、`compose`（Nacos 注册发现 + lb 路由 + RabbitMQ）。`SPRING_PROFILES_ACTIVE` 切换。
- JWT 密钥：`prism.jwt.secret=prism-jwt-secret-please-change-in-prod-0123456789`（各服务 yml 默认一致，可用 `PRISM_JWT_SECRET` 覆盖）。
- 上游 LLM（chat 渠道表配置）；Python：`PRISM_LLM_BASE_URL`（默认 mock）、`PRISM_LLM_API_KEY`、`PRISM_LLM_MODEL`、`PRISM_EMBED_PROVIDER=hash|text2vec`（默认 hash，零下载可跑）、`PRISM_KB_BASE`（回调 kb 的地址，默认 http://127.0.0.1:8104）、`MQ_MODE=inmemory|rabbit`。
- 应用上传目录 `data/kb`（.gitignore 排除）。

## 8. 网关路由表（application-local.yml 直连 / compose 用 lb://）

| 断言 | 目标 |
|---|---|
| Path=/v1/** | chat(8102) |
| Path=/api/auth/**, /api/apps/**, /api/channels/** | user(8101) |
| Path=/api/usage/**, /api/bills/** | billing(8103) |
| Path=/api/kb/** | kb(8104) |
| Path=/api/agent/** | agent(8200)（去掉 /api 前缀→/agent? 否：Python 也挂 /api/agent 路径，网关原样转发） |

外部 `/internal/**` 一律 403。

## 9. 构建/运行命令

- 根目录：`mvn -T 1C clean package -DskipTests`（首次编译全部模块）
- 单模块：`mvn -pl prism-chat -am package -DskipTests`
- 前端：`cd frontend && npm i && npm run dev`（vite proxy → http://localhost:8080）
- Python：`cd agent-service && py -3.12 -m venv .venv && .venv pip install -r requirements.txt && uvicorn app.main:app --port 8200`
- compose：`docker compose up -d`（deploy/docker-compose.yml）
