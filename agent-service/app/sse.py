"""SSE 输出工具（事件协议见 CONTRACTS.md §6.6，非 OpenAI 格式）。"""

from __future__ import annotations

import json
from typing import Any


def sse_event(event: str, data: Any) -> str:
    """格式化为一段 SSE：``event: <event>\\ndata: <json>\\n\\n``。"""
    payload = data if isinstance(data, str) else json.dumps(data, ensure_ascii=False)
    return f"event: {event}\ndata: {payload}\n\n"


SSE_HEADERS = {
    "Cache-Control": "no-cache",
    "Connection": "keep-alive",
    "X-Accel-Buffering": "no",  # 告知 nginx 反代不要缓冲
}
