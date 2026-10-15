"""Prism agent-service 入口：FastAPI 装配 + lifespan 启动 MQ 消费者。

启动：``uvicorn app.main:app --port 8200``
"""

from __future__ import annotations

import asyncio
import logging
from contextlib import asynccontextmanager, suppress

from fastapi import FastAPI

from app.config import get_settings
from app.routers import agent as agent_router
from app.routers import chat as chat_router
from app.routers import internal as internal_router
from app.services.task_consumer import TaskConsumer, get_pipeline

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    consumer = TaskConsumer(get_pipeline(), settings)
    task = asyncio.create_task(consumer.run(), name="kb-task-consumer")
    logger.info("agent-service 启动（MQ_MODE=%s）", settings.mq_mode)
    yield
    task.cancel()
    with suppress(asyncio.CancelledError):
        await task
    logger.info("agent-service 已停止")


app = FastAPI(title="Prism Agent Service", version="1.0.0", lifespan=lifespan)

app.include_router(chat_router.router)
app.include_router(agent_router.router)
app.include_router(internal_router.router)


@app.get("/healthz")
async def healthz():
    settings = get_settings()
    return {
        "status": "ok",
        "service": "agent-service",
        "mqMode": settings.mq_mode,
        "embedProvider": settings.embed_provider,
        "llm": settings.llm_base_url,
    }
