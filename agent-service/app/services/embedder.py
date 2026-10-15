"""Embedding 抽象：默认 HashEmbedder（离线零下载），可选 text2vec。

切换（CONTRACTS §7）：``PRISM_EMBED_PROVIDER=hash|text2vec``。
"""

from __future__ import annotations

import hashlib
import logging
from typing import Protocol

import numpy as np

from app.config import get_settings

logger = logging.getLogger(__name__)

EMBED_DIM = 512


class Embedder(Protocol):
    """统一接口：texts -> 向量列表（与输入顺序一致）。"""

    def embed(self, texts: list[str]) -> list[list[float]]:
        ...


class HashEmbedder:
    """字符 bigram 哈希 + TF + L2 归一化的离线近似 embedding（512 维）。

    **这是离线演示实现**，不引入任何语义模型：
    - 对文本的字符 bigram（中文按字、英文按相邻两字符）做 md5 哈希，落桶到 512 维；
    - 桶内累加词频（TF），最后整体 L2 归一化 —— 余弦相似度≈归一化向量的内积，
      对「字面重叠」类 query 有基本的召回能力，语义等价改写无能为力。
    - 确定性：md5 + 固定维度，跨进程/重启结果完全一致，可安全重跑幂等。

    生产环境请切 ``PRISM_EMBED_PROVIDER=text2vec``（SentenceTransformer 中文模型）
    或接远程 embedding 服务；写入与查询必须使用同一实现，切换后需重建向量库。
    """

    def __init__(self, dim: int = EMBED_DIM) -> None:
        self.dim = dim

    @staticmethod
    def _bigrams(text: str) -> list[str]:
        text = text.replace("\n", " ").strip()
        if len(text) < 2:
            return [text] if text else []
        return [text[i : i + 2] for i in range(len(text) - 1)]

    def _vector(self, text: str) -> np.ndarray:
        vec = np.zeros(self.dim, dtype=np.float32)
        for gram in self._bigrams(text):
            bucket = int(hashlib.md5(gram.encode("utf-8")).hexdigest(), 16) % self.dim
            vec[bucket] += 1.0
        norm = float(np.linalg.norm(vec))
        if norm > 0:
            vec /= norm
        return vec

    def embed(self, texts: list[str]) -> list[list[float]]:
        return [self._vector(t).tolist() for t in texts]


class Text2vecEmbedder:
    """可选的 text2vec 实现（requirements-text2vec.txt）。

    懒加载 SentenceTransformer；import/加载失败自动回退 HashEmbedder 并打日志说明。
    """

    def __init__(self, model_name: str) -> None:
        self.model_name = model_name
        self._model = None

    def _load(self):
        if self._model is None:
            from sentence_transformers import SentenceTransformer  # 懒加载

            logger.info("加载 text2vec 模型: %s ...", self.model_name)
            self._model = SentenceTransformer(self.model_name)
        return self._model

    def embed(self, texts: list[str]) -> list[list[float]]:
        model = self._load()
        vectors = model.encode(texts, normalize_embeddings=True, show_progress_bar=False)
        return [v.tolist() for v in vectors]


def build_embedder():
    settings = get_settings()
    if settings.embed_provider == "text2vec":
        try:
            import sentence_transformers  # noqa: F401

            logger.info("PRISM_EMBED_PROVIDER=text2vec，使用 SentenceTransformer embedding")
            return Text2vecEmbedder(settings.text2vec_model)
        except ImportError:
            logger.warning(
                "PRISM_EMBED_PROVIDER=text2vec 但未安装 sentence-transformers"
                "（pip install -r requirements-text2vec.txt），回退 HashEmbedder（离线近似）"
            )
    return HashEmbedder()


_cached: Embedder | None = None


def get_embedder() -> Embedder:
    """进程内单例（text2vec 模型加载昂贵，只加载一次）。"""
    global _cached
    if _cached is None:
        _cached = build_embedder()
    return _cached
