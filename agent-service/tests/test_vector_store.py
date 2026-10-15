"""VectorStore：tmp_path chroma roundtrip + purge + 幂等 id。"""

from app.services.embedder import HashEmbedder
from app.services.vector_store import ChunkIn, VectorStore, chunk_id


def _store(tmp_path):
    return VectorStore(path=str(tmp_path))


def test_roundtrip_and_purge_doc(tmp_path):
    store = _store(tmp_path)
    emb = HashEmbedder()
    texts = ["知识库向量存储检索测试第一段内容", "与主题完全无关的另一段天气内容"]
    chunks = [ChunkIn(doc_id=5, chunk_index=i, content=t, doc_name="a.txt") for i, t in enumerate(texts)]
    store.upsert(1, chunks, emb.embed(texts))

    hits = store.query(1, emb.embed(["知识库向量存储检索测试"])[0], top_k=2)
    assert hits, "应有召回"
    assert hits[0].content == texts[0]  # 最相似的是同主题文本
    assert 0.15 < hits[0].score <= 1.0
    assert hits[0].doc_name == "a.txt"
    assert hits[0].chunk_index == 0

    store.purge(1, doc_id=5)
    assert store.query(1, emb.embed(["知识库向量存储检索测试"])[0], top_k=2) == []


def test_purge_whole_kb(tmp_path):
    store = _store(tmp_path)
    emb = HashEmbedder()
    texts = ["整库删除测试"]
    store.upsert(2, [ChunkIn(doc_id=9, chunk_index=0, content=texts[0], doc_name="b.txt")], emb.embed(texts))
    assert store.query(2, emb.embed(texts)[0], 1)

    result = store.purge(2)  # docId 为空 → 整库删 collection
    assert result["scope"] == "kb"
    assert store.query(2, emb.embed(texts)[0], 1) == []


def test_upsert_idempotent_ids(tmp_path):
    store = _store(tmp_path)
    emb = HashEmbedder()
    chunk = ChunkIn(doc_id=7, chunk_index=0, content="幂等重跑内容", doc_name="c.txt")
    store.upsert(3, [chunk], emb.embed([chunk.content]))
    store.upsert(3, [chunk], emb.embed([chunk.content]))  # 重跑：uuid5 覆盖而非追加
    assert len(store.query(3, emb.embed(["幂等重跑内容"])[0], 5)) == 1
    assert chunk_id(7, 0) == chunk_id(7, 0)
    assert chunk_id(7, 0) != chunk_id(7, 1)


def test_query_missing_kb_returns_empty(tmp_path):
    store = _store(tmp_path)
    assert store.query(404, [0.0] * 512, 3) == []
    assert store.purge(404)["scope"] == "kb"  # 不存在的库删除不报错
