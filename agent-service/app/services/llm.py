"""LLM 抽象：MockLLM（默认，离线确定性）与 OpenAI 兼容实现。

接口：
    async astream(prompt, history=[]) -> AsyncIterator[str]   流式增量
    async agenerate(prompt, history=[]) -> str                整段生成

切换（CONTRACTS §7）：PRISM_LLM_BASE_URL=mock 走 MockLLM；
否则按 OpenAI 兼容协议调 {base_url}/chat/completions。
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
from typing import AsyncIterator

import httpx

from app.config import Settings, get_settings

logger = logging.getLogger(__name__)


class BaseLLM:
    async def astream(self, prompt: str, history: list[dict] | None = None) -> AsyncIterator[str]:
        raise NotImplementedError

    async def agenerate(self, prompt: str, history: list[dict] | None = None) -> str:
        chunks: list[str] = []
        async for delta in self.astream(prompt, history):
            chunks.append(delta)
        return "".join(chunks)


class MockLLM(BaseLLM):
    """确定性中文模拟回答（离线演示，不调任何外部 API）。

    策略：
    - prompt 中带编号引用块（形如 ``[1] ...``，即 RAG 组装好的上下文）时，
      解析出每个编号的首句关键词，生成「根据知识库[n]，……」的结构化回答；
    - 否则按「问题关键词」生成模板回答，并明确标注这是离线 mock；
    - 流式按 2~4 字符/段 yield，每段 sleep 0.05s，模拟真实打字机效果。
    """

    _QUESTION_RE = re.compile(r"^问题[:：]\s*(.+)$", re.M)  # 行首锚定，避免匹配系统提示里的「回答问题：」
    _REF_RE = re.compile(r"\[(\d+)\][^\[]{2,}")
    _SLEEP = 0.05

    @staticmethod
    def _snippet(text: str, limit: int = 40) -> str:
        text = text.strip().replace("\n", " ")
        return text[:limit] + ("…" if len(text) > limit else "")

    def _answer(self, prompt: str) -> str:
        m = self._QUESTION_RE.search(prompt)
        question = m.group(1).strip() if m else prompt.strip()[:60]
        topic = self._snippet(question, 24)

        refs = [int(n) for n in re.findall(r"^\[(\d+)\]", prompt, flags=re.M)]
        if refs:
            lines = ["根据知识库检索结果，回答如下："]
            for n in refs:
                block_m = re.search(
                    rf"^\[{n}\]\s*(.+)$", prompt, flags=re.M
                )
                kw = self._snippet(block_m.group(1), 36) if block_m else ""
                lines.append(f"根据知识库[{n}]，{kw}；")
            lines.append(f"综上，关于「{topic}」的结论以上述引用为准，建议结合原文核对。")
            return "\n".join(lines)
        return (
            f"关于「{topic}」：当前为离线 MockLLM 模拟回答（未接入真实模型）。"
            f"如需真实回答，请将 PRISM_LLM_BASE_URL 配置为任意 OpenAI 兼容服务地址。"
        )

    async def astream(self, prompt: str, history: list[dict] | None = None) -> AsyncIterator[str]:
        answer = self._answer(prompt)
        i = 0
        while i < len(answer):
            step = 2 + (i % 3)  # 2~4 字符
            yield answer[i : i + step]
            i += step
            await asyncio.sleep(self._SLEEP)


class OpenAICompatibleLLM(BaseLLM):
    """OpenAI 兼容真实实现（httpx 流式）。

    调 ``{base_url}/chat/completions``，PRISM_LLM_API_KEY / PRISM_LLM_MODEL。
    """

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self._client = httpx.AsyncClient(timeout=settings.llm_timeout)

    async def aclose(self) -> None:
        await self._client.aclose()

    def _messages(self, prompt: str, history: list[dict] | None) -> list[dict]:
        msgs = list(history or [])
        msgs.append({"role": "user", "content": prompt})
        return msgs

    async def astream(self, prompt: str, history: list[dict] | None = None) -> AsyncIterator[str]:
        payload = {
            "model": self.settings.llm_model,
            "messages": self._messages(prompt, history),
            "stream": True,
        }
        headers = {"Authorization": f"Bearer {self.settings.llm_api_key}"}
        url = self.settings.llm_base_url.rstrip("/") + "/chat/completions"
        async with self._client.stream("POST", url, json=payload, headers=headers) as resp:
            resp.raise_for_status()
            async for line in resp.aiter_lines():
                if not line.startswith("data:"):
                    continue
                data = line[5:].strip()
                if data == "[DONE]":
                    break
                try:
                    obj = json.loads(data)
                except json.JSONDecodeError:
                    continue
                delta = obj.get("choices", [{}])[0].get("delta", {}).get("content")
                if delta:
                    yield delta

    async def agenerate(self, prompt: str, history: list[dict] | None = None) -> str:
        payload = {
            "model": self.settings.llm_model,
            "messages": self._messages(prompt, history),
            "stream": False,
        }
        headers = {"Authorization": f"Bearer {self.settings.llm_api_key}"}
        url = self.settings.llm_base_url.rstrip("/") + "/chat/completions"
        resp = await self._client.post(url, json=payload, headers=headers)
        resp.raise_for_status()
        obj = resp.json()
        return obj["choices"][0]["message"]["content"]


def build_llm(settings: Settings | None = None) -> BaseLLM:
    settings = settings or get_settings()
    if not settings.llm_base_url or settings.llm_base_url.lower() == "mock":
        logger.info("PRISM_LLM_BASE_URL=mock，使用 MockLLM（离线确定性回答）")
        return MockLLM()
    logger.info("使用 OpenAI 兼容 LLM: %s (model=%s)", settings.llm_base_url, settings.llm_model)
    return OpenAICompatibleLLM(settings)
