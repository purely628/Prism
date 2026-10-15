# Prism · LLM 能力开放平台

> 把「调用大模型」做成企业级 API 商品：对外 **OpenAI 兼容**一个入口，对内做多模型路由、流量治理、语义缓存、Token 计量计费与配额管控，并内置 LangChain RAG 知识库与 ReAct Agent。
>
> Java 微服务做工程底座，Python/LangChain 做 AI 编排——一个"传统后端 + AI"的完整样本。

```mermaid
flowchart TB
    subgraph client[" "]
        UI["Vue3 控制台<br/>(Nginx 托管/反代)"]
    end
    GW["Spring Cloud Gateway :8080<br/>JWT / API Key 鉴权 · Redis+Lua 令牌桶限流"]
    UI --> GW
    subgraph java["Java 微服务（Spring Boot 3.4）"]
        USER["prism-user :8101<br/>账号/应用/API Key/渠道"]
        CHAT["prism-chat :8102<br/>OpenAI 兼容 · SSE 透传<br/>语义缓存 · 加权路由/故障转移"]
        BILL["prism-billing :8103<br/>MQ 消费记账(幂等/攒批)<br/>配额 · 对账 · 账单"]
        KB["prism-kb :8104<br/>知识库编排 · 任务派发"]
    end
    GW --> USER
    GW --> CHAT
    GW --> BILL
    GW --> KB
    subgraph ai["Python AI 编排"]
        AGENT["agent-service :8200<br/>FastAPI + LangChain<br/>RAG 问答(引用溯源) · ReAct Agent"]
        CHROMA["Chroma 向量库"]
        AGENT --- CHROMA
    end
    GW --> AGENT
    MQ{{"消息队列<br/>local: Redis Stream / prod: RabbitMQ"}}
    CHAT -- "用量事件" --> MQ
    MQ -- "消费记账" --> BILL
    KB -- "解析任务" --> MQ
    MQ -- "消费解析" --> AGENT
    AGENT -. "回调状态/下载文件" .-> KB
    MYSQL[("MySQL prism<br/>10 张业务表")]
    REDIS[("Redis<br/>缓存/限流/配额/Stream")]
    USER --- MYSQL
    KB --- MYSQL
    BILL --- MYSQL
    CHAT --- REDIS
    GW --- REDIS
    BILL --- REDIS
```

## 为什么做这个项目

企业接入大模型的真痛点不在"调通 API"，而在接入之后：密钥怎么管、流量怎么限、成本怎么算、多模型怎么切换、知识库怎么统一。Prism 把这些做成平台能力：

- **接入方**只需一个 API Key，把 SDK 的 `base_url` 换成 Prism 即可（完全 OpenAI 协议兼容）；
- **平台方**获得限流、配额、计量、账单、多渠道路由与故障转移、语义缓存降本；
- **业务方**还能直接使用平台内置的 RAG 知识库问答与 ReAct Agent 能力。

## 架构

```
                        ┌────────────────────────┐
   Vue3 控制台 ────────► │  Nginx（静态/反代/SSE） │
                        └───────────┬────────────┘
                                    ▼
                    ┌────────────────────────────┐
   /v1/** API Key   │  Spring Cloud Gateway      │  JWT / API Key 鉴权
   /api/** JWT      │  Redis+Lua 令牌桶限流       │  OpenAI 风格错误
                    └──┬──────┬──────┬──────┬────┘
          ┌────────────┘      │      │      └──────────────┐
          ▼                   ▼      ▼                     ▼
   ┌────────────┐   ┌────────────┐ ┌────────────┐   ┌────────────────────┐
   │ prism-user │   │ prism-chat │ │ prism-kb   │   │ agent-service      │
   │ 账号/应用/  │   │ OpenAI兼容 │ │ 知识库编排  │   │ FastAPI+LangChain  │
   │ 渠道管理    │   │ SSE透传    │ │ 文档/任务   │   │ RAG问答+ReAct      │
   │ :8101      │   │ 语义缓存   │ │ 派发/回调   │   │ Agent :8200        │
   └────────────┘   │ 路由/熔断   │ └─────┬──────┘   └─────────┬──────────┘
                    │ :8102      │       │ 派发/回调            │ 消费
                    └─────┬──────┘       ▼                      │
                          │ 用量事件   (MQ: Stream/Rabbit) ◄────┘
                          ▼
                   ┌────────────┐
                   │prism-billing│ 消费记账（幂等/攒批）/ 对账 / 账单
                   │ :8103       │
                   └────────────┘
   基础设施：MySQL · Redis（缓存/限流/配额/Stream）· RabbitMQ/Nacos(compose) · Chroma
```

## 核心技术亮点

| # | 亮点 | 说明 |
|---|---|---|
| 1 | **OpenAI 协议兼容网关** | 客户端零改造接入；/v1 错误体、SSE chunk、usage 字段全部对齐 OpenAI 规范 |
| 2 | **Redis+Lua 令牌桶限流** | 网关层按应用维度限流，Lua 原子完成"补充+扣减"，集群安全 |
| 3 | **语义缓存** | 对 prompt 向量化（本地哈希 embedding，可切远程服务），余弦相似度 ≥0.92 命中即回放，重复问答 0 上游成本 |
| 4 | **多渠道路由与故障转移** | 同名模型多渠道加权随机；连续失败 3 次自动摘除（计数 TTL 5min 半开恢复），调用侧透明重试 |
| 5 | **WebFlux SSE 流式透传** | 全链路响应式，逐 token 转发的同时完成 usage 统计，不缓冲整包 |
| 6 | **异步计量计费** | 用量走消息队列（本地 Redis Stream / 生产 RabbitMQ 可切换），消费端 SETNX+唯一索引双幂等，攒批入库削峰写压力 |
| 7 | **配额与对账** | Redis 计数器月配额管控（软限制）；每日对账 MySQL 流水 vs Redis 计数，漂移自动修正留痕 |
| 8 | **Java 编排 + Python AI** | kb 服务派发解析任务，Python worker 消费：LangChain 切分 → embedding → Chroma；RAG 回答带引用溯源；ReAct Agent 真实工具调用 |
| 9 | **双运行形态** | `local` profile 零中间件依赖（MySQL+Redis 即可全链路跑通）；`compose` profile 一键起 Nacos/RabbitMQ/Nginx 完整生产拓扑 |

## 快速开始（local 模式）

依赖：JDK 17+、Maven、MySQL 8、Redis、Python 3.12、Node 20。

```bash
# 1. 初始化数据库（会创建 prism 库、种子渠道与演示应用）
mysql -h127.0.0.1 -uroot -p < sql/init.sql

# 2. 编译后端
mvn clean package -DskipTests

# 3. 启动五个后端服务（各开一个终端）
java -jar prism-user/target/prism-user-1.0.0.jar
java -jar prism-gateway/target/prism-gateway-1.0.0.jar
java -jar prism-chat/target/prism-chat-1.0.0.jar
java -jar prism-billing/target/prism-billing-1.0.0.jar
java -jar prism-kb/target/prism-kb-1.0.0.jar

# 4. 启动 Python AI 服务（离线 mock 模式，无需任何模型 Key）
cd agent-service
py -3.12 -m venv .venv && .venv/Scripts/pip install -r requirements.txt
.venv/Scripts/uvicorn app.main:app --port 8200

# 5. 启动前端
cd frontend && npm i && npm run dev   # http://localhost:5173  admin / admin123
```

60 秒体验 OpenAI 兼容接口（默认走内置 mock 渠道，离线可跑）：

```bash
curl -N http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer sk-prism-demo0000demo0000demo0000demo0000" \
  -H "Content-Type: application/json" \
  -d '{"model":"prism-demo","messages":[{"role":"user","content":"用一句话介绍你自己"}],"stream":true}'
```

要接真实模型：控制台「模型渠道」里给 GLM/DeepSeek 渠道补上 api_key 即可（OpenAI 兼容协议）。

## 完整生产拓扑（compose 模式）

```bash
mvn clean package -DskipTests && cd frontend && npm run build && cd ..
docker compose -f deploy/docker-compose.yml up -d
```

包含：Nacos（注册/配置）、RabbitMQ（usage/kb 任务真实 MQ）、Nginx（前端托管 + 反代 + SSE 不缓冲配置 + limit_req）、全部服务容器化。

## 项目结构

```
prism
├── prism-gateway      # SCG 网关：JWT/API Key 鉴权、Lua 令牌桶限流、路由
├── prism-user         # 账号、应用与 API Key、模型渠道管理
├── prism-chat         # OpenAI 兼容推理：路由/故障转移、SSE 透传、语义缓存、用量发布
├── prism-billing      # MQ 消费记账（幂等/攒批）、配额、对账、账单、用量查询
├── prism-kb           # 知识库编排：文档上传、任务派发、Python 回调、检索代理
├── agent-service      # FastAPI + LangChain：文档切分入库、RAG 问答（引用溯源）、ReAct Agent
├── frontend           # Vue3 控制台：应用/渠道/用量看板/知识库/RAG/Playground
├── deploy             # docker-compose、nginx.conf、服务 Dockerfile
├── sql/init.sql       # 建库建表 + 种子数据
└── docs               # 架构与接口契约
```

## 技术栈

**后端**：JDK 17 · Spring Boot 3.4 · Spring Cloud Gateway · WebFlux · MyBatis-Plus · MySQL · Redis（Lua/Stream）· RabbitMQ · Caffeine · JWT
**AI**：Python 3.12 · FastAPI · LangChain（ReAct Agent / TextSplitters / Prompts）· Chroma · embedding 可插拔（本地哈希 / text2vec / 远程服务）
**前端**：Vue3 · Vite · Pinia · Element Plus · ECharts
**部署**：Nginx（SSE 反代优化）· Docker Compose · Nacos

## 已知边界与演进方向（诚实声明）

- 单库多服务：演示环境共库分表；生产应按服务拆库或走服务间接口
- 配额为软限制：预检只看已用量，请求增量事后入账，临界会少量超出（对账兜底）
- 语义缓存在单实例内存（Caffeine）：多实例应下沉 Redis/向量库
- Token 计量为估算（CJK×1 + 其他÷4）：上游返回真实 usage 时优先采用
- 消费失败兜底简化：Stream 模式靠 pending+XCLAIM 恢复，未做完整重试队列
- 安全为演示级：JWT 固定密钥、内网头信任模型，生产需 mTLS/网关签名

## License

MIT
