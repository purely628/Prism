"""MockLLM：流式产出非空、确定性、带上下文的结构化回答。"""

from app.services.llm import MockLLM


async def test_astream_nonempty_and_chunked():
    llm = MockLLM()
    parts = [p async for p in llm.astream("问题：什么是知识库？")]
    assert parts, "流式应产出内容"
    assert all(p for p in parts), "不应有空增量"
    assert len(parts) >= 5, "应切成多段（打字机效果）"
    full = "".join(parts)
    assert "知识库" in full


async def test_agenerate_equals_stream_join():
    llm = MockLLM()
    prompt = "问题：MockLLM 的一致性如何？"
    streamed = "".join([p async for p in llm.astream(prompt)])
    generated = await llm.agenerate(prompt)
    assert streamed == generated  # 确定性：两次生成完全一致


async def test_answer_uses_numbered_context():
    prompt = (
        "参考资料：\n\n[1] (假期制度.txt #0) 年假制度为每年15天，入职满一年后生效。\n\n"
        "问题：年假有几天？"
    )
    answer = await MockLLM().agenerate(prompt)
    assert "根据知识库[1]" in answer
    assert "15天" in answer
    assert "年假" in answer


async def test_answer_without_context_marks_mock():
    answer = await MockLLM().agenerate("问题：模型多少钱？")
    assert "MockLLM" in answer or "mock" in answer.lower()
    assert "模型多少钱" in answer
