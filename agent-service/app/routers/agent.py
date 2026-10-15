"""ReAct Agent 路由：POST /api/agent/run（CONTRACTS §6.5/§6.6）。"""

from __future__ import annotations

from fastapi import APIRouter
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from app.services.agent_core import run_agent_once, run_agent_stream
from app.sse import SSE_HEADERS

router = APIRouter()


class AgentRunRequest(BaseModel):
    goal: str = Field(min_length=1, max_length=4000)
    stream: bool = True


@router.post("/api/agent/run")
async def agent_run(req: AgentRunRequest):
    if req.stream:
        return StreamingResponse(
            run_agent_stream(req.goal),
            media_type="text/event-stream",
            headers=SSE_HEADERS,
        )
    return await run_agent_once(req.goal)
