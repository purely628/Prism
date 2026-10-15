"""HashEmbedder：维度 / L2 归一化 / 确定性。"""

import math

from app.services.embedder import EMBED_DIM, HashEmbedder


def test_dim_and_normalization():
    emb = HashEmbedder()
    vecs = emb.embed(["知识库向量测试", "hello world", "中文english混合123"])
    assert len(vecs) == 3
    for v in vecs:
        assert len(v) == EMBED_DIM
        norm = math.sqrt(sum(x * x for x in v))
        assert abs(norm - 1.0) < 1e-5


def test_deterministic_across_instances():
    a = HashEmbedder().embed(["跨实例确定性"])[0]
    b = HashEmbedder().embed(["跨实例确定性"])[0]
    assert a == b  # md5 哈希落桶，跨进程/重启一致（幂等入库的前提）


def test_different_texts_different_vectors():
    a, b = HashEmbedder().embed(["入职流程说明", "天气预报明天"])
    assert a != b


def test_empty_and_short_text():
    vecs = HashEmbedder().embed(["", "短"])
    assert len(vecs) == 2 and len(vecs[0]) == EMBED_DIM  # 空文本为零向量（不归一化）
