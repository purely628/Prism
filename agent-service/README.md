# agent-service · AI 编排服务（FastAPI + LangChain）

Prism 的 AI 能力承载层：文档解析入库（切分 → embedding → Chroma）、RAG 问答（引用溯源）、ReAct Agent（真实工具调用）。Java 侧（prism-kb）负责编排与状态，本服务只管模型生态。

## 模块结构

```
app/
├── main.py                 # FastAPI 入口 + lifespan 启动任务消费者
├── config.py               # pydantic-settings（全部 PRISM_* 环境变量）
├── routers/
│   ├── chat.py             # POST /api/kb/{kbId}/chat      RAG 问答（SSE，契约 §6.6）
│   ├── agent.py            # POST /api/agent/run           ReAct Agent（SSE）
│   └── internal.py         # /internal/kb/{id}/retrieve|purge、/internal/parse、/embed
└── services/
    ├── llm.py              # LLM 抽象：MockLLM（默认，离线确定性）/ OpenAI 兼容真实实现
    ├── embedder.py         # HashEmbedder（默认 512 维离线）/ text2vec 可选
    ├── vector_store.py     # Chroma 封装（uuid5 幂等 upsert / 查询 / 清理）
    ├── parser.py           # pdf/docx/txt/md → 文本
    ├── rag.py              # 检索 → ChatPromptTemplate 编号引用 → 流式 + citations
    ├── agent_core.py       # AgentExecutor + create_react_agent + 3 个 @tool
    └── task_consumer.py    # 消费 kb 解析任务（Redis Stream 消费组 / aio-pika）
```

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `PRISM_KB_BASE` | http://127.0.0.1:8104 | 回调 kb 服务（参数/文件/状态） |
| `MQ_MODE` | stream | stream=Redis Stream 消费组；rabbit=aio-pika |
| `PRISM_REDIS_URL` | redis://127.0.0.1:6379/0 | Redis 连接 |
| `PRISM_LLM_BASE_URL` | mock | `mock`=内置确定性模拟；否则 OpenAI 兼容地址 |
| `PRISM_LLM_API_KEY` / `PRISM_LLM_MODEL` | — | 真实 LLM 的密钥与模型名 |
| `PRISM_EMBED_PROVIDER` | hash | hash=离线哈希向量；text2vec=本地中文模型（需装 extras） |

## 离线/真实切换

默认全离线（mock LLM + hash embedding），`clone` 即跑：

```bash
py -3.12 -m venv .venv
.venv/Scripts/pip install -r requirements.txt -i https://pypi.tuna.tsinghua.edu.cn/simple
.venv/Scripts/uvicorn app.main:app --port 8200
pytest -q   # 28 个离线单测
```

接真实模型：设 `PRISM_LLM_BASE_URL=https://open.bigmodel.cn/api/paas/v4`、`PRISM_LLM_API_KEY=...`、`PRISM_LLM_MODEL=glm-4-flash`；切真向量：装 `requirements-text2vec.txt` 并设 `PRISM_EMBED_PROVIDER=text2vec`。

## 设计说明

- **MockReActChatModel**：mock 模式下决策策略是脚本化的（首轮必检索/可计算，拿到观察后汇总），但走的是**真实 AgentExecutor 循环**与真实工具执行——离线演示与真实 LLM 共用同一套链路，切换只换模型实现。
- **幂等**：Chroma id = uuid5(f"{docId}:{chunkIndex}")，消息重投/文档重解析 upsert 覆盖；回调 chunks 由 kb 先删后插。
- **至少一次投递**：任务处理成功才 ACK；kb 回调不可达时消息留在 pending，恢复后 XCLAIM 重投。
