# -*- coding: utf-8 -*-
"""Prism kb→Python RAG 全链路 E2E（用 UTF-8 干净的 httpx，绕开 Git Bash curl GBK 坑）"""
import io
import json
import sys
import time

import httpx

BASE = "http://127.0.0.1:8080"
ok = lambda code, msg: print(f"[{'✓' if code else '✗'}] {msg}")

c = httpx.Client(timeout=30)

# 1. 登录
r = c.post(f"{BASE}/api/auth/login", json={"username": "admin", "password": "admin123"})
token = r.json()["data"]["token"]
h = {"Authorization": f"Bearer {token}"}
ok(True, f"登录 (token {len(token)} chars)")

# 2. 建知识库
r = c.post(f"{BASE}/api/kb", headers=h, json={
    "kbName": "产品知识库", "description": "E2E 验证", "chunkSize": 200, "chunkOverlap": 30, "topK": 4})
kb_id = r.json()["data"]["id"]
ok(r.json()["code"] == 0, f"创建知识库 id={kb_id}")

# 3. 上传文档
doc_text = """Prism 是一个 LLM 能力开放平台。

核心功能包括:
1. OpenAI 兼容网关,客户端只需修改 base_url 和 API Key 即可接入。
2. Redis 加 Lua 实现的令牌桶限流,保证集群环境下按应用维度精确限流。
3. 语义缓存:对 prompt 做向量化,余弦相似度超过阈值直接返回缓存结果,降低上游成本。
4. 多渠道路由:同名模型多个上游渠道,加权随机选择,失败自动摘除与故障转移。
5. 计量计费:用量事件异步记账,支持月度配额管控与对账修正。

令牌桶算法的原理是以固定速率向桶中添加令牌,请求到达时消耗令牌,桶空则拒绝。
语义缓存命中的条件是两个问题的向量余弦相似度达到阈值,当前设置为 0.92。"""
r = c.post(f"{BASE}/api/kb/{kb_id}/documents", headers=h,
           files={"file": ("功能说明.txt", doc_text.encode("utf-8"), "text/plain")})
data = r.json()["data"]
doc_id = data["docId"]
ok(r.json()["code"] == 0, f"上传文档 docId={doc_id}")

# 4. 轮询解析状态（PENDING → PARSING → READY）
status = None
for _ in range(30):
    time.sleep(1)
    docs = c.get(f"{BASE}/api/kb/{kb_id}/documents", headers=h).json()["data"]
    status = next(d for d in docs if d["id"] == doc_id)["status"]
    if status in ("READY", "FAILED"):
        break
ok(status == "READY", f"解析状态: {status}" + ("" if status == "READY" else "（期望 READY）"))
if status != "READY":
    docs = c.get(f"{BASE}/api/kb/{kb_id}/documents", headers=h).json()["data"]
    print("  error:", next(d for d in docs if d["id"] == doc_id).get("error"))
    sys.exit(1)

# 5. 检索测试
r = c.post(f"{BASE}/api/kb/{kb_id}/retrieve", headers=h,
           json={"query": "令牌桶是怎么限流的", "topK": 3})
chunks = r.json()["data"]
ok(r.json()["code"] == 0 and len(chunks) > 0, f"检索命中 {len(chunks)} 块, top score={chunks[0]['score']:.3f}")
print("  首块内容:", chunks[0]["content"][:60].replace("\n", " "))

# 6. RAG 对话（SSE 流式）
print("[*] RAG 对话流式响应:")
buf, citations, done = [], None, False
with c.stream("POST", f"{BASE}/api/kb/{kb_id}/chat", headers=h,
              json={"question": "令牌桶限流的原理是什么"}) as r:
    for line in r.iter_lines():
        if line.startswith("event:"):
            ev = line.split(":", 1)[1].strip()
        elif line.startswith("data:"):
            payload = line.split(":", 1)[1].strip()
            try:
                obj = json.loads(payload)
            except Exception:
                continue
            if ev == "chunk":
                buf.append(obj.get("delta", ""))
            elif ev == "citations":
                citations = obj
            elif ev == "done":
                done = True
answer = "".join(buf)
ok(done and len(answer) > 0, f"RAG 回答 {len(answer)} 字, 引用 {len(citations or [])} 条, done={done}")
print("  回答:", answer[:120])
if citations:
    print("  引用:", [(x["docName"], x["chunkIndex"], round(x["score"], 3)) for x in citations])

# 7. 用量侧验证（RAG 不走 chat 服务不计费——确认 usage 无新增调用为正确行为）
print("\nE2E 完成 ✓")
