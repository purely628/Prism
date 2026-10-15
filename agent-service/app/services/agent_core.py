"""ReAct Agent：LangChain AgentExecutor + create_react_agent，真实工具调用循环。

- 工具（langchain_core.tools.tool）：kb_search / calculator / current_time。
- LLM 适配：mock 模式用 MockReActChatModel（BaseChatModel 子类，决策策略脚本化）；
  真实模式用 langchain-openai ChatOpenAI（requirements-openai.txt，可选依赖）。
- 流式：AgentExecutor.astream(stream_runnable=False) 拿中间步骤
  （actions/steps/output），映射为 CONTRACTS §6.6 的 thought/action/observation/answer。
"""

from __future__ import annotations

import ast
import asyncio
import datetime as _dt
import json
import logging
import operator
import re
from typing import Any, AsyncIterator

from langchain.agents import AgentExecutor, create_react_agent
from langchain_core.callbacks import AsyncCallbackHandler
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage, BaseMessage
from langchain_core.outputs import ChatGeneration, ChatResult
from langchain_core.prompts import PromptTemplate
from langchain_core.tools import tool
from pydantic import BaseModel, Field

from app.config import get_settings
from app.sse import sse_event

logger = logging.getLogger(__name__)

# ============================ 安 全 计 算 器 ============================

_OPS: dict[type, Any] = {
    ast.Add: operator.add,
    ast.Sub: operator.sub,
    ast.Mult: operator.mul,
    ast.Div: operator.truediv,
    ast.FloorDiv: operator.floordiv,
    ast.Mod: operator.mod,
    ast.Pow: operator.pow,
    ast.USub: operator.neg,
    ast.UAdd: operator.pos,
}


def safe_calculate(expression: str) -> str:
    """AST 白名单安全求值（禁止 eval 裸调，只允许数字与 + - * / // % ** 和括号）。"""
    expression = (expression or "").strip()
    if len(expression) > 200:
        return "错误：表达式过长"
    try:
        tree = ast.parse(expression, mode="eval")
        value = _eval_node(tree.body)
    except ZeroDivisionError:
        return "错误：除数为 0"
    except Exception as exc:  # noqa: BLE001 —— 非法输入统一转成可读错误
        return f"错误：不支持的表达式（{type(exc).__name__}: {exc}）"
    if isinstance(value, float) and value.is_integer():
        value = int(value)
    return f"{expression} = {value}"


def _eval_node(node: ast.AST):
    if isinstance(node, ast.Constant) and isinstance(node.value, (int, float)) and not isinstance(node.value, bool):
        return node.value
    if isinstance(node, ast.BinOp) and type(node.op) in _OPS:
        left, right = _eval_node(node.left), _eval_node(node.right)
        if isinstance(node.op, ast.Pow) and abs(right) > 1000:
            raise ValueError("指数过大")
        return _OPS[type(node.op)](left, right)
    if isinstance(node, ast.UnaryOp) and type(node.op) in _OPS:
        return _OPS[type(node.op)](_eval_node(node.operand))
    raise ValueError(f"不允许的语法元素: {type(node).__name__}")


# ============================ 工 具 定 义 ============================


@tool
def kb_search(query: str, kb_id: int = 1) -> str:
    """在企业知识库中检索与 query 最相关的文档片段（top3）。

    参数：query 为检索关键词；kb_id 为知识库 ID（整数，默认 1）。
    Action Input 支持两种写法：直接给检索词（kb_id 取默认），或单行 JSON，
    如 {"query": "入职流程", "kb_id": 1}。
    """
    # ReAct 的 Action Input 是单串：langchain 会把它整体作为第一个参数传入，
    # 这里兼容整串 JSON 的写法，手动展开。
    if query and query.strip().startswith("{"):
        try:
            obj = json.loads(query)
            if isinstance(obj, dict) and "query" in obj:
                query = str(obj.get("query"))
                kb_id = int(obj.get("kb_id", kb_id))
        except (json.JSONDecodeError, TypeError, ValueError):
            pass
    from app.services.rag import retrieve

    try:
        hits = retrieve(int(kb_id), query, 3)
    except Exception as exc:  # noqa: BLE001 —— 工具异常转成 observation，不炸循环
        return f"检索失败: {type(exc).__name__}: {exc}"
    if not hits:
        return f"知识库 {kb_id} 中未检索到与「{query}」相关的内容（可能尚未导入文档）"
    lines = [f"[{i}] {h.doc_name or '未命名'}#chunk{h.chunk_index} (score={h.score:.3f}) {h.content[:100]}"
             for i, h in enumerate(hits, 1)]
    return "\n".join(lines)


@tool
def calculator(expression: str) -> str:
    """算术计算器：计算 expression（只支持数字和 + - * / // % ** 括号）。

    参数：expression 为算术表达式字符串，如 (12+8)*3。
    """
    return safe_calculate(expression)


@tool
def current_time() -> str:
    """获取当前时间（无参数），格式 yyyy-MM-dd HH:mm:ss。"""
    return _dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def build_tools() -> list:
    return [kb_search, calculator, current_time]


# ============================ Mock ReAct 模 型 ============================


class MockReActChatModel(BaseChatModel):
    """离线演示用的 ReAct 决策模型（BaseChatModel 子类）。

    **决策策略是脚本化的**（不是神经网络）：AgentExecutor 真实走 ReAct 循环、
    真实调用工具，本类只负责在每轮产出符合 ReAct 文法的输出：
    1. 第一轮（prompt 中无 Observation）按目标启发式选工具：
       - 目标含算式/「计算/多少」→ calculator（正则抽取算式）；
       - 目标问时间/日期 → current_time；
       - 否则工具列表里有 kb_search 就先检索（Action Input 用单行 JSON）。
    2. 拿到 Observation 后返回 Final Answer，汇总 Observation 内容。
    接真实模型时换 langchain-openai ChatOpenAI，循环与工具零改动。
    """

    @property
    def _llm_type(self) -> str:
        return "mock-react"

    @staticmethod
    def _tail(prompt: str) -> str:
        """取最后一个 'Question:' 之后的内容（用户目标 + agent_scratchpad）。

        ReAct 提示词的格式说明区本身就含 'Observation:' 字样，
        判断「是否已拿到工具结果」必须只看 scratchpad 区域。
        """
        return prompt.rsplit("Question:", 1)[-1] if "Question:" in prompt else prompt

    def _extract_question(self, prompt: str) -> str:
        tail = self._tail(prompt)
        m = re.search(r"^(.+?)(?=\n|$)", tail.strip())
        question = m.group(1).strip() if m else prompt.strip()[:60]
        # 去掉 scratchpad 里混入的多轮 Thought 等前缀，取第一行即用户目标
        return question.split("\nThought:")[0][:80]

    def _first_round(self, prompt: str) -> str:
        question = self._extract_question(prompt)
        expr_m = re.search(r"[-(]?\d[\d\s+\-*/().×÷%]*\d\)?|[-(]?\d\b", question)
        if ("计算" in question or "多少" in question or "等于" in question) and expr_m:
            expr = expr_m.group(0).replace("×", "*").replace("÷", "/")
            return (
                f"Thought: 这是一个算术问题，我需要用计算器求值。\n"
                f"Action: calculator\n"
                f"Action Input: {expr}"
            )
        if any(k in question for k in ("时间", "几点", "日期", "今天", "现在")):
            return (
                "Thought: 用户想知道当前时间，我调用时间工具。\n"
                "Action: current_time\n"
                "Action Input: "
            )
        if "kb_search" in prompt:
            payload = json.dumps({"query": question[:50], "kb_id": 1}, ensure_ascii=False)
            return (
                f"Thought: 我先检索知识库，查找与「{question[:30]}」相关的资料。\n"
                f"Action: kb_search\n"
                f"Action Input: {payload}"
            )
        return (
            "Thought: 没有更合适的工具，我查询当前时间辅助回答。\n"
            "Action: current_time\n"
            "Action Input: "
        )

    def _final_round(self, prompt: str) -> str:
        tail = self._tail(prompt)
        observations = re.findall(
            r"Observation:\s*(.+?)(?=\nThought:|\nAction:|\Z)", tail, flags=re.S
        )
        summary = (observations[-1] if observations else "（无工具结果）").strip()
        summary = re.sub(r"\s+", " ", summary)[:300]
        return (
            "Thought: 我已经拿到工具结果，可以给出最终答案。\n"
            f"Final Answer: 根据工具执行结果：{summary}。（离线 MockReAct 演示策略生成，"
            "接真实模型后由 LLM 自主决策。）"
        )

    def _react_text(self, prompt: str) -> str:
        if "Observation:" in self._tail(prompt):
            return self._final_round(prompt)
        return self._first_round(prompt)

    def _generate(self, messages, stop=None, run_manager=None, **kwargs) -> ChatResult:
        prompt = "\n".join(m.content for m in messages if isinstance(m.content, str))
        text = self._react_text(prompt)
        return ChatResult(generations=[ChatGeneration(message=AIMessage(content=text))])


def build_chat_model() -> BaseChatModel:
    """mock（默认）或 langchain-openai ChatOpenAI（可选依赖）。"""
    settings = get_settings()
    if not settings.llm_base_url or settings.llm_base_url.lower() == "mock":
        return MockReActChatModel()
    try:
        from langchain_openai import ChatOpenAI
    except ImportError as exc:
        raise RuntimeError(
            "真实模式需要 langchain-openai：pip install -r requirements-openai.txt"
        ) from exc
    return ChatOpenAI(
        base_url=settings.llm_base_url,
        api_key=settings.llm_api_key or "EMPTY",
        model=settings.llm_model,
        temperature=0,
        streaming=False,
    )


# ============================ ReAct Prompt / Executor ============================

REACT_PROMPT = PromptTemplate.from_template(
    """你是一个能使用工具的推理助手，按 ReAct 范式一步步解决问题。

你可以使用下列工具：
{tools}

严格使用以下格式作答（不要添加多余内容）：

Question: 需要回答的输入问题
Thought: 思考下一步该做什么
Action: 要执行的动作，必须是 [{tool_names}] 之一
Action Input: 动作的输入（kb_search 有多个参数，用单行 JSON：{{"query": "...", "kb_id": 1}}）
Observation: 工具返回的结果
...（Thought/Action/Action Input/Observation 可重复多次）
Thought: 我已知道最终答案
Final Answer: 对原始问题的最终回答

开始！

Question: {input}
Thought:{agent_scratchpad}"""
)


def build_executor(tools: list | None = None, chat_model: BaseChatModel | None = None) -> AgentExecutor:
    agent = create_react_agent(
        llm=chat_model or build_chat_model(),
        tools=tools or build_tools(),
        prompt=REACT_PROMPT,
    )
    return AgentExecutor(
        agent=agent,
        tools=tools or build_tools(),
        verbose=False,
        handle_parsing_errors=True,
        handle_tool_error=True,
        max_iterations=5,
        max_execution_time=60,
    )


# ============================ 事 件 流 ============================

_THOUGHT_RE = re.compile(r"Thought:\s*(.*?)(?=\nAction:|\Z)", flags=re.S)


def _thought_from_log(log: str) -> str:
    m = _THOUGHT_RE.search(log or "")
    return re.sub(r"\s+", " ", m.group(1)).strip()[:300] if m else ""


class _StepEventQueue(AsyncCallbackHandler):
    """把 AgentExecutor 的中间步骤实时推入 asyncio.Queue（thought/action/observation）。

    langchain 0.3.30 的 AgentExecutor.astream 只在末尾吐最终 output，
    中间步骤通过 callbacks（on_agent_action / on_tool_end）实时获取。
    """

    def __init__(self, queue: asyncio.Queue) -> None:
        self.queue = queue

    async def on_agent_action(self, action, *, run_id, parent_run_id=None, tags=None, **kwargs):
        thought = _thought_from_log(getattr(action, "log", ""))
        if thought:
            await self.queue.put(("thought", {"text": thought}))
        tool_input = getattr(action, "tool_input", "")
        await self.queue.put(
            (
                "action",
                {
                    "tool": action.tool,
                    "input": tool_input if isinstance(tool_input, str)
                    else json.dumps(tool_input, ensure_ascii=False),
                },
            )
        )

    async def on_tool_end(self, output, *, run_id, parent_run_id=None, tags=None, **kwargs):
        await self.queue.put(("observation", {"output": str(output)[:800]}))


async def iter_agent_events(goal: str) -> AsyncIterator[tuple[str, dict]]:
    """产出 (event, data) 序列：thought / action / observation / answer / done。

    AgentExecutor 真实执行工具循环；中间步骤经 callbacks 实时流出，
    Final Answer 最后切成 answer delta。异常向上抛，由调用方转 error 事件。
    """
    executor = build_executor()
    queue: asyncio.Queue = asyncio.Queue()
    final: dict = {"answer": ""}

    async def _consume() -> None:
        try:
            async for chunk in executor.astream(
                {"input": goal}, config={"callbacks": [_StepEventQueue(queue)]}
            ):
                if isinstance(chunk, dict) and chunk.get("output") is not None:
                    final["answer"] = str(chunk["output"])
        finally:
            await queue.put(None)  # 结束哨兵

    consumer = asyncio.create_task(_consume())
    try:
        while True:
            item = await queue.get()
            if item is None:
                break
            yield item
    finally:
        if not consumer.done():
            await consumer

    answer = final["answer"] or "（Agent 未能产出最终答案）"
    for i in range(0, len(answer), 6):  # Final Answer 切成 delta 流
        yield ("answer", {"delta": answer[i : i + 6]})
    yield ("done", {"goal": goal})


async def run_agent_stream(goal: str) -> AsyncIterator[str]:
    """SSE 版（CONTRACTS §6.6）：event: thought/action/observation/answer/done/error。"""
    try:
        async for event, data in iter_agent_events(goal):
            yield sse_event(event, data)
    except Exception as exc:  # noqa: BLE001
        logger.exception("Agent 运行失败: %s", goal)
        yield sse_event("error", {"message": f"{type(exc).__name__}: {exc}"})


async def run_agent_once(goal: str) -> dict:
    """stream=false：聚合为单 JSON（含中间步骤）。"""
    steps: list[dict] = []
    answer_parts: list[str] = []
    async for event, data in iter_agent_events(goal):
        if event == "action":
            steps.append({"tool": data["tool"], "input": data["input"]})
        elif event == "observation":
            if steps:
                steps[-1]["output"] = data["output"]
        elif event == "answer":
            answer_parts.append(data["delta"])
    return {"answer": "".join(answer_parts), "steps": steps}
