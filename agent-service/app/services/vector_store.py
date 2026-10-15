"""Chroma 向量库封装（PersistentClient，cosine 空间，telemetry 关闭）。

- 目录：``agent-service/data/chroma``（PRISM_CHROMA_DIR 可覆盖）。
- collection 命名 ``kb_{kbId}``，每知识库一个 collection。
- chunk id = uuid5(namespace, f"{docId}:{chunkIndex}")，重复导入天然幂等（upsert 覆盖）。
"""

from __future__ import annotations

import logging
import uuid
from dataclasses import dataclass, field
from typing import Any

import chromadb
from chromadb.config import Settings as ChromaSettings

from app.config import get_settings

logger = logging.getLogger(__name__)

_NAMESPACE = uuid.uuid5(uuid.NAMESPACE_URL, "prism-agent-service/chunk")


def chunk_id(doc_id: int | str, chunk_index: int) -> str:
    """确定性向量 ID：同一 docId+chunkIndex 重跑结果一致（幂等 upsert）。"""
    return str(uuid.uuid5(_NAMESPACE, f"{doc_id}:{chunk_index}"))


@dataclass
class ChunkIn:
    """待写入的一个文档块。"""

    doc_id: int
    chunk_index: int
    content: str
    doc_name: str = ""
    extra_metadata: dict[str, Any] = field(default_factory=dict)


@dataclass
class Hit:
    """检索命中。score = 1 - cosine distance，越大越相似。"""

    content: str
    score: float
    doc_name: str
    chunk_index: int
    metadata: dict[str, Any] = field(default_factory=dict)


class VectorStore:
    def __init__(self, path: str | None = None) -> None:
        self.path = path or get_settings().chroma_dir
        self.client = chromadb.PersistentClient(
            path=self.path,
            settings=ChromaSettings(anonymized_telemetry=False, allow_reset=False),
        )

    # ---- collection ----
    def _collection(self, kb_id: int | str):
        return self.client.get_or_create_collection(
            name=f"kb_{kb_id}",
            metadata={"hnsw:space": "cosine"},
        )

    # ---- 写 ----
    def upsert(self, kb_id: int | str, chunks: list[ChunkIn], vectors: list[list[float]]) -> None:
        if len(chunks) != len(vectors):
            raise ValueError("chunks 与 vectors 数量不一致")
        if not chunks:
            return
        col = self._collection(kb_id)
        col.upsert(
            ids=[chunk_id(c.doc_id, c.chunk_index) for c in chunks],
            embeddings=vectors,
            documents=[c.content for c in chunks],
            metadatas=[
                {
                    "docId": str(c.doc_id),
                    "docName": c.doc_name or f"doc-{c.doc_id}",
                    "chunkIndex": c.chunk_index,
                    **{k: v for k, v in c.extra_metadata.items() if isinstance(v, (str, int, float, bool))},
                }
                for c in chunks
            ],
        )
        logger.info("kb=%s upsert %s chunks", kb_id, len(chunks))

    # ---- 读 ----
    def query(
        self,
        kb_id: int | str,
        vector: list[float],
        top_k: int = 4,
        where: dict | None = None,
    ) -> list[Hit]:
        try:
            col = self.client.get_collection(name=f"kb_{kb_id}")
        except Exception:  # chromadb 抛 ValueError/NotFoundError 视为库不存在
            return []
        if col.count() == 0:
            return []
        res = col.query(query_embeddings=[vector], n_results=min(top_k, col.count()), where=where)
        hits: list[Hit] = []
        ids = res.get("ids", [[]])[0]
        docs = res.get("documents", [[]])[0]
        metas = res.get("metadatas", [[]])[0]
        dists = res.get("distances", [[]])[0]
        for _id, doc, meta, dist in zip(ids, docs, metas, dists):
            meta = meta or {}
            hits.append(
                Hit(
                    content=doc or "",
                    score=round(1.0 - float(dist), 6),
                    doc_name=str(meta.get("docName", "")),
                    chunk_index=int(meta.get("chunkIndex", 0)),
                    metadata=meta,
                )
            )
        return hits

    # ---- 删 ----
    def purge(self, kb_id: int | str, doc_id: int | str | None = None) -> dict:
        """docId 为空 → 整库删除 collection；否则只删该文档的所有块。"""
        name = f"kb_{kb_id}"
        if doc_id is None or doc_id == "":
            try:
                self.client.delete_collection(name=name)
                logger.info("purge collection %s", name)
                return {"scope": "kb", "kbId": kb_id}
            except Exception:
                return {"scope": "kb", "kbId": kb_id, "note": "collection 不存在（视为已清）"}
        try:
            col = self.client.get_collection(name=name)
        except Exception:
            return {"scope": "doc", "docId": doc_id, "note": "collection 不存在（视为已清）"}
        col.delete(where={"docId": str(doc_id)})
        logger.info("purge kb=%s doc=%s", kb_id, doc_id)
        return {"scope": "doc", "kbId": kb_id, "docId": doc_id}


_store: VectorStore | None = None


def get_vector_store() -> VectorStore:
    global _store
    if _store is None:
        _store = VectorStore()
    return _store
