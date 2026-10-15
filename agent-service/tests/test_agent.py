"""ReAct Agent：离线全链路（AgentExecutor 真实循环 + 真实工具调用）。"""

from app.services.agent_core import current_time, iter_agent_events, kb_search
from app.services.embedder import HashEmbedder
from app.services.vector_store import ChunkIn, VectorStore


async def test_agent_calculator_flow():
    """算术目标：thought → action(calculator) → observation → answer → done。"""
    events = [e async for e in iter_agent_events("计算 (12+8)*3 等于多少")]
    kinds = [k for k, _ in events]

    assert kinds[0] == "thought"
    action = next(d for k, d in events if k == "action")
    assert action["tool"] == "calculator"
    assert action["input"].replace(" ", "") == "(12+8)*3"

    observation = next(d for k, d in events if k == "observation")
    assert "60" in observation["output"]

    answer = "".join(d["delta"] for k, d in events if k == "answer")
    assert "60" in answer
    assert kinds[-1] == "done"


async def test_agent_kb_search_flow(tmp_path, monkeypatch):
    """检索目标：先 kb_search 再总结；kb_search 单串 JSON 入参兼容。"""
    store = VectorStore(path=str(tmp_path))
    emb = HashEmbedder()
    text = "新员工入职流程：先在人事系统提交入职登记，再办理工牌，最后参加入职培训。"
    store.upsert(1, [ChunkIn(doc_id=10, chunk_index=0, content=text, doc_name="入职指南.txt")], emb.embed([text]))
    monkeypatch.setattr("app.services.rag.get_vector_store", lambda: store)

    events = [e async for e in iter_agent_events("公司新员工入职流程是什么")]
    action = next(d for k, d in events if k == "action")
    assert action["tool"] == "kb_search"
    observation = next(d for k, d in events if k == "observation")
    assert "入职指南.txt" in observation["output"]

    answer = "".join(d["delta"] for k, d in events if k == "answer")
    assert "入职" in answer


def test_kb_search_tool_plain_and_json_input(tmp_path, monkeypatch):
    store = VectorStore(path=str(tmp_path))
    emb = HashEmbedder()
    text = "报销流程：填写报销单，附上发票，提交部门经理审批。"
    vectors = emb.embed([text])
    chunk = [ChunkIn(doc_id=11, chunk_index=0, content=text, doc_name="报销.pdf")]
    store.upsert(1, chunk, vectors)  # 默认 kb_id=1
    store.upsert(2, chunk, vectors)  # 显式 kb_id=2
    monkeypatch.setattr("app.services.rag.get_vector_store", lambda: store)

    plain = kb_search.invoke("报销流程")  # 纯文本 Action Input（kb_id 走默认 1）
    assert "报销.pdf" in plain
    as_json = kb_search.invoke('{"query": "报销流程", "kb_id": 2}')  # 单行 JSON Action Input
    assert "报销.pdf" in as_json


def test_kb_search_empty_kb_graceful():
    out = kb_search.invoke('{"query": "任意", "kb_id": 9999}')
    assert "未检索到" in out  # 空库不抛异常，返回可读 observation


def test_current_time_tool():
    import re

    out = current_time.invoke({})
    assert re.match(r"\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}", out)
