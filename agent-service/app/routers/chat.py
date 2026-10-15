"""RAG 问答路由：POST /api/kb/{kbId}/chat（CONTRACTS §6.4/§6.6）。"""

from __future__ import annotations

import json

from fastapi import APIRouter
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from app.services import rag
from app.sse import SSE_HEADERS

router = APIRouter()


class ChatRequest(BaseModel):
    question: str = Field(min_length=1, max_length=4000)
    stream: bool = True
    topK: int | None = Field(default=None, ge=1, le=20)


@router.post("/api/kb/{kb_id}/chat")
async def kb_chat(kb_id: int, req: ChatRequest):
    if req.stream:
        return StreamingResponse(
            rag.chat_stream(kb_id, req.question, req.topK),
            media_type="text/event-stream",
            headers=SSE_HEADERS,
        )
    result = await rag.chat_once(kb_id, req.question, req.topK)
    return json.loads(json.dumps(result, ensure_ascii=False))
