"""Prism agent-service：FastAPI + LangChain（RAG / ReAct Agent / 文档解析入库）。

包级初始化：在任何 chromadb/posthog 导入前关闭遥测，
避免 Windows 上首次启动尝试上报导致卡住（离线硬要求）。
"""

from __future__ import annotations

import os

# chromadb 遥测开关必须在 chromadb 导入前设置（Dockerfile 里也设置了同名的 ENV，双保险）
os.environ.setdefault("ANONYMIZED_TELEMETRY", "False")
os.environ.setdefault("CHROMA_TELEMETRY_IMPL", "noop")  # chroma 0.5.x: 直接指定 noop 实现
