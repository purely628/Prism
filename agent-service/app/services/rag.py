"""RAG 问答：检索 → ChatPromptTemplate 组装（编号上下文）→ LLM 流式 → SSE 事件。

事件协议（CONTRACTS §6.6）：
    event: chunk     data {"delta": ".."}
    event: citations data [{docName, chunkIndex, score, snippet}]
    event: done      data {"promptTokens": n, "completionTokens": n}
    event: error     data {"message": ".."}
"""

from __future__ import annotations

import logging
from typing import AsyncIterator

from langchain_core.prompts import ChatPromptTemplate

from app.config import get_settings
from app.services.embedder import get_embedder
from app.services.llm import build_llm
from app.services.vector_store import Hit, get_vector_store
from app.sse import sse_event
from app.utils import estimate_tokens

logger = logging.getLogger(__name__)

SYSTEM_TEMPLATE = """\
你是一个严谨的企业知识库问答助手。只依据下面给出的编号参考资料回答问题：
- 回答中引用资料时必须标注编号，如「根据知识库[2]……」；
- 资料中没有的内容要明确说明「知识库中未提及」，不要编造；
- 用中文、分点作答，简洁准确。"""

USER_TEMPLATE = """\
参考资料：
{context}

问题：{question}

要求：先给出直接结论，再补充依据，并标注引用编号。"""

_prompt = ChatPromptTemplate.from_messages(
    [("system", SYSTEM_TEMPLATE), ("user", USER_TEMPLATE)]
)


def build_context(hits: list[Hit]) -> str:
    """把检索命中组装成 [1]..[n] 编号块（RAG 引用溯源的锚点）。"""
    return "\n\n".join(
        f"[{i}] ({hit.doc_name} #{hit.chunk_index}) {hit.content}" for i, hit in enumerate(hits, 1)
    )


def build_prompt(question: str, hits: list[Hit]) -> str:
    """LangChain ChatPromptTemplate 组装（system+user），压成单字符串供 LLM 抽象消费。"""
    messages = _prompt.invoke({"context": build_context(hits), "question": question}).to_messages()
    return "\n\n".join(m.content for m in messages)


def retrieve(kb_id: int, question: str, top_k: int | None = None) -> list[Hit]:
    """问题向量化 → chroma topK → 低于阈值(默认0.15)的丢弃。"""
    settings = get_settings()
    top_k = top_k or settings.default_top_k
    vector = get_embedder().embed([question])[0]
    hits = get_vector_store().query(kb_id, vector=vector, top_k=top_k)
    return [h for h in hits if h.score >= settings.rag_score_threshold]


def citations_payload(hits: list[Hit]) -> list[dict]:
    return [
        {
            "docName": h.doc_name,
            "chunkIndex": h.chunk_index,
            "score": h.score,
            "snippet": h.content[:80],
        }
        for h in hits
    ]


async def chat_stream(kb_id: int, question: str, top_k: int | None = None) -> AsyncIterator[str]:
    """SSE 流式问答（失败时产出 event: error，不让连接静默断掉）。"""
    llm = build_llm()
    try:
        hits = retrieve(kb_id, question, top_k)
        prompt = build_prompt(question, hits)
        answer_parts: list[str] = []
        async for delta in llm.astream(prompt):
            answer_parts.append(delta)
            yield sse_event("chunk", {"delta": delta})
        yield sse_event("citations", citations_payload(hits))
        answer = "".join(answer_parts)
        yield sse_event(
            "done",
            {
                "promptTokens": estimate_tokens(prompt),
                "completionTokens": estimate_tokens(answer),
            },
        )
    except Exception as exc:  # noqa: BLE001 —— SSE 通道内任何错误都转成 error 事件
        logger.exception("RAG chat 失败 kb=%s", kb_id)
        yield sse_event("error", {"message": f"{type(exc).__name__}: {exc}"})


async def chat_once(kb_id: int, question: str, top_k: int | None = None) -> dict:
    """stream=false 的单 JSON 返回。"""
    hits = retrieve(kb_id, question, top_k)
    prompt = build_prompt(question, hits)
    answer = await build_llm().agenerate(prompt)
    return {
        "answer": answer,
        "citations": citations_payload(hits),
        "promptTokens": estimate_tokens(prompt),
        "completionTokens": estimate_tokens(answer),
    }
