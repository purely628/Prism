"""RAG prompt 组装：ChatPromptTemplate + 编号引用块。"""

from app.services.rag import build_prompt, citations_payload
from app.services.vector_store import Hit


def _hits():
    return [
        Hit(content="年假制度为每年15天。" * 5, score=0.91, doc_name="假期制度.txt", chunk_index=0),
        Hit(content="试用期6个月，期间享受全额薪资。" * 5, score=0.85, doc_name="入职手册.docx", chunk_index=2),
    ]


def test_prompt_contains_numbered_refs_and_question():
    prompt = build_prompt("年假有几天？", _hits())
    assert "[1] (假期制度.txt #0)" in prompt
    assert "[2] (入职手册.docx #2)" in prompt
    assert "问题：年假有几天？" in prompt
    assert "编号" in prompt  # 系统提示要求引用标注
    assert "资料" in prompt


def test_prompt_no_hits_still_assembles():
    prompt = build_prompt("随便问点啥", [])
    assert "问题：随便问点啥" in prompt
    assert "[]" not in prompt.split("问题：")[0]  # 没有引用时不产生空编号


def test_citations_payload_snippet_80():
    payload = citations_payload([Hit(content="字" * 200, score=0.7, doc_name="x.txt", chunk_index=3)])
    assert payload[0]["docName"] == "x.txt"
    assert payload[0]["chunkIndex"] == 3
    assert payload[0]["score"] == 0.7
    assert len(payload[0]["snippet"]) == 80
