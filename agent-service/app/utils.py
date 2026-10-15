"""小工具：token 估算（与全平台口径一致）。"""

from __future__ import annotations

import math
import re

_CJK_RE = re.compile(r"[\u4e00-\u9fff\u3040-\u30ff\uac00-\ud7af]")


def estimate_tokens(text: str) -> int:
    """粗略 token 估算：中文字符（CJK）×1 + 其他字符 ÷4，向上取整。

    与 prism-chat / billing 的演示口径保持一致；上游返回真实 usage 时优先采用真实值。
    """
    if not text:
        return 0
    cjk = len(_CJK_RE.findall(text))
    others = len(text) - cjk
    return cjk + math.ceil(others / 4)
