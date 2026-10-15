"""内部接口（CONTRACTS §6.5）——仅内网互调，网关拒绝外部 /internal。"""

from __future__ import annotations

import logging

from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel, Field

from app.services.embedder import get_embedder
from app.services.vector_store import get_vector_store

logger = logging.getLogger(__name__)

router = APIRouter()


class EmbedRequest(BaseModel):
    texts: list[str] = Field(min_length=1, max_length=64)


class RetrieveRequest(BaseModel):
    query: str = Field(min_length=1, max_length=4000)
    topK: int = Field(default=4, ge=1, le=20)


@router.post("/embed")
async def embed(req: EmbedRequest):
    """给 chat 服务语义缓存用（可选）。"""
    vectors = get_embedder().embed(req.texts)
    return {"vectors": vectors}


@router.post("/internal/kb/{kb_id}/purge")
async def purge_kb(kb_id: int, doc_id: int | None = Query(default=None, alias="docId")):
    """docId 空 → 整库删 collection；否则删单文档向量。"""
    try:
        result = get_vector_store().purge(kb_id, doc_id)
        return {"purged": True, **result}
    except Exception as exc:  # noqa: BLE001
        logger.exception("purge 失败 kb=%s doc=%s", kb_id, doc_id)
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@router.post("/internal/kb/{kb_id}/retrieve")
async def retrieve(kb_id: int, req: RetrieveRequest):
    """向量检索（kb 服务代理调用）。

    检索测试是诊断工具：返回原始 topK（不套 0.15 阈值），让使用者看到真实分数分布；
    RAG 问答链路（rag.retrieve）仍保留阈值过滤，防止低分噪声污染回答。
    """
    from app.services.embedder import get_embedder
    from app.services.vector_store import get_vector_store

    vector = get_embedder().embed([req.query])[0]
    hits = get_vector_store().query(kb_id, vector=vector, top_k=req.topK)
    return [
        {
            "content": h.content,
            "score": h.score,
            "docName": h.doc_name,
            "chunkIndex": h.chunk_index,
        }
        for h in hits
    ]


@router.post("/internal/parse")
async def parse(body: dict):
    """HTTP 直推解析（kb 服务 local 模式可绕过 MQ 直接调用）。

    body: {"docId": int, "kbId": int} —— 后台执行，立即返回受理结果。
    """
    try:
        doc_id = int(body["docId"])
        kb_id = int(body["kbId"])
    except (KeyError, TypeError, ValueError) as exc:
        raise HTTPException(status_code=400, detail="需要 docId/kbId 两个整数字段") from exc

    import asyncio

    from app.services.task_consumer import get_pipeline

    asyncio.create_task(_safe_process(doc_id, kb_id))
    return {"accepted": True, "docId": doc_id, "kbId": kb_id}


async def _safe_process(doc_id: int, kb_id: int) -> None:
    try:
        await get_pipeline().handle_message(doc_id, kb_id)
    except Exception:  # noqa: BLE001 —— 后台任务，异常已由 handle_message 记日志/回调
        pass
