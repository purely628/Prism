"""kb 解析任务消费与处理管道（CONTRACTS §5 / §6.4）。

消费（MQ_MODE，默认 stream）：
- stream：redis.asyncio 消费组 ``agent-service`` 阻塞读 ``prism:stream:kb``；
  启动 XGROUP CREATE（BUSYGROUP 忽略）+ XPENDING/XCLAIM 认领 idle>5min 的悬滞消息。
- rabbit：aio-pika 消费 ``prism.kb.task``（exchange prism.kb.direct / routing key kb.task）。

消息体：``{"docId": x, "kbId": y}``（kb 服务已确认）。处理成功才 ACK；
kb 服务回调不可达时不 ACK（Stream 留 pending 由认领重投 / Rabbit requeue）。

处理管道：
    GET /internal/kbs/{kbId}          → chunkSize/chunkOverlap/topK
    GET /internal/documents/{docId}/file → 文件字节
    parser.parse_bytes → split_text → embed → chroma upsert
    POST /internal/documents/{docId}/status → READY+chunks / FAILED+error
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
from email.utils import unquote

import httpx

from app.config import Settings, get_settings
from app.services.embedder import get_embedder
from app.services.parser import parse_bytes, split_text
from app.services.vector_store import ChunkIn, get_vector_store
from app.utils import estimate_tokens

logger = logging.getLogger(__name__)


def _unwrap(data: dict) -> dict:
    """Java 统一 Result<T>（{"code":0,"data":...}）与裸 DTO 兼容。"""
    if isinstance(data, dict) and "code" in data and "data" in data:
        if int(data.get("code", -1)) != 0:
            raise RuntimeError(f"kb 服务返回错误: {data.get('code')} {data.get('message')}")
        return data["data"] if isinstance(data["data"], dict) else {}
    return data


def _filename_from_headers(headers: httpx.Headers, doc_id: int) -> str:
    """从 Content-Disposition 提取文件名（兼容 filename* 与裸 filename），兜底 doc-{id}。"""
    cd = headers.get("content-disposition", "")
    m = re.search(r"filename\*=(?:UTF-8|utf-8)''([^;]+)", cd)
    if m:
        return unquote(m.group(1).strip('" '))
    m = re.search(r'filename="?([^";]+)"?', cd)
    if m:
        return m.group(1)
    return f"doc-{doc_id}"


class ParsePipeline:
    """单个文档的 解析→切分→向量化→入库→回调 管道（每个 docId 一把锁去重）。"""

    def __init__(self, settings: Settings | None = None, http: httpx.AsyncClient | None = None) -> None:
        self.settings = settings or get_settings()
        self._http = http or httpx.AsyncClient(timeout=self.settings.llm_timeout)
        self._locks: dict[int, asyncio.Lock] = {}
        self._locks_guard = asyncio.Lock()

    async def _lock_for(self, doc_id: int) -> asyncio.Lock:
        async with self._locks_guard:
            return self._locks.setdefault(doc_id, asyncio.Lock())

    # ---- kb 服务交互 ----
    async def fetch_kb_config(self, kb_id: int) -> dict:
        resp = await self._http.get(f"{self.settings.kb_base}/internal/kbs/{kb_id}")
        resp.raise_for_status()
        data = _unwrap(resp.json())
        return {
            "chunkSize": int(data.get("chunkSize") or 500),
            "chunkOverlap": int(data.get("chunkOverlap") or 50),
            "topK": int(data.get("topK") or 4),
        }

    async def fetch_document(self, doc_id: int) -> tuple[str, bytes]:
        resp = await self._http.get(f"{self.settings.kb_base}/internal/documents/{doc_id}/file")
        resp.raise_for_status()
        name = _filename_from_headers(resp.headers, doc_id)
        return name, resp.content

    async def report_status(self, doc_id: int, payload: dict) -> None:
        resp = await self._http.post(
            f"{self.settings.kb_base}/internal/documents/{doc_id}/status", json=payload
        )
        resp.raise_for_status()  # 回调不可达 → 抛异常 → 消息不 ACK

    # ---- 主流程 ----
    async def process(self, doc_id: int, kb_id: int) -> dict:
        lock = await self._lock_for(doc_id)
        async with lock:  # 同 docId 并发消息串行化；重跑靠 uuid5 upsert 幂等
            cfg = await self.fetch_kb_config(kb_id)
            filename, data = await self.fetch_document(doc_id)
            text = parse_bytes(filename, data)
            pieces = split_text(
                text, chunk_size=cfg["chunkSize"], chunk_overlap=cfg["chunkOverlap"]
            )
            if not pieces:
                raise ValueError(f"文档解析结果为空: {filename}")

            vectors = get_embedder().embed(pieces)
            chunks = [
                ChunkIn(
                    doc_id=doc_id,
                    chunk_index=i,
                    content=piece,
                    doc_name=filename,
                )
                for i, piece in enumerate(pieces)
            ]
            get_vector_store().upsert(kb_id, chunks, vectors)

            await self.report_status(
                doc_id,
                {
                    "status": "READY",
                    "chunkCount": len(pieces),
                    "chunks": [
                        {
                            "chunkIndex": c.chunk_index,
                            "content": c.content,
                            "tokenCount": estimate_tokens(c.content),
                        }
                        for c in chunks
                    ],
                },
            )
            logger.info("文档解析完成 doc=%s kb=%s chunks=%s", doc_id, kb_id, len(pieces))
            return {"docId": doc_id, "kbId": kb_id, "chunkCount": len(pieces)}

    async def handle_message(self, doc_id: int, kb_id: int) -> None:
        """入口：成功 ACK；失败回调 FAILED，回调也失败则抛出（不 ACK）。"""
        try:
            await self.process(doc_id, kb_id)
        except Exception as exc:  # noqa: BLE001
            logger.exception("文档解析失败 doc=%s kb=%s", doc_id, kb_id)
            error = f"{type(exc).__name__}: {exc}"[:500]
            try:
                await self.report_status(doc_id, {"status": "FAILED", "error": error})
            except Exception:
                logger.exception("FAILED 回调不可达 doc=%s，消息将留在 pending 重投", doc_id)
                raise  # 回调不可达 → 不 ACK，等 kb 恢复后重投


class TaskConsumer:
    """MQ 消费者：stream（Redis Stream，默认）/ rabbit（aio-pika）。"""

    def __init__(self, pipeline: ParsePipeline, settings: Settings | None = None) -> None:
        self.settings = settings or get_settings()
        self.pipeline = pipeline

    async def run(self) -> None:
        while True:
            try:
                if self.settings.mq_mode == "rabbit":
                    await self._run_rabbit()
                else:
                    await self._run_stream()
                return
            except asyncio.CancelledError:
                logger.info("任务消费者退出")
                raise
            except Exception as exc:  # noqa: BLE001 —— 中间件断连自愈
                logger.warning("MQ 连接异常（%s），3s 后重试: %s", self.settings.mq_mode, exc)
                await asyncio.sleep(3)

    # ---------- Redis Stream ----------
    async def _run_stream(self) -> None:
        import redis.asyncio as aioredis

        s = self.settings
        r = aioredis.from_url(s.redis_url, decode_responses=True)
        try:
            try:
                await r.xgroup_create(s.kb_stream, s.consumer_group, id="0", mkstream=True)
            except Exception as exc:  # BUSYGROUP → 组已存在，忽略
                if "BUSYGROUP" not in str(exc):
                    raise
            logger.info("Redis Stream 消费启动: stream=%s group=%s", s.kb_stream, s.consumer_group)

            while True:
                await self._claim_pending(r)
                resp = await r.xreadgroup(
                    s.consumer_group,
                    s.consumer_name,
                    {s.kb_stream: ">"},
                    count=5,
                    block=5000,
                )
                if not resp:
                    continue
                for _stream, messages in resp:
                    for msg_id, fields in messages:
                        await self._handle_stream_message(r, msg_id, fields)
        finally:
            await r.aclose()

    async def _claim_pending(self, r) -> None:
        """认领 idle>5min 的 pending 消息（简单故障恢复；生产可换 XAUTOCLAIM）。"""
        s = self.settings
        try:
            pendings = await r.xpending_range(
                s.kb_stream, s.consumer_group, "-", "+", 50, idle=s.claim_idle_ms
            )
        except Exception:
            return
        for p in pendings:
            msg_id = p.get("message_id")
            if not msg_id:
                continue
            logger.info("认领 pending 消息 %s（idle %sms）", msg_id, p.get("time_since_delivered"))
            claimed = await r.xclaim(
                s.kb_stream, s.consumer_group, s.consumer_name, s.claim_idle_ms, [msg_id]
            )
            for msg_id2, fields in claimed or []:
                await self._handle_stream_message(r, msg_id2, fields)

    async def _handle_stream_message(self, r, msg_id: str, fields: dict) -> None:
        try:
            payload = _extract_payload(fields)
            await self.pipeline.handle_message(int(payload["docId"]), int(payload["kbId"]))
            await r.xack(self.settings.kb_stream, self.settings.consumer_group, msg_id)
        except KeyError as exc:
            logger.error("消息体缺少字段 %s，丢弃: %s", exc, fields)
            await r.xack(self.settings.kb_stream, self.settings.consumer_group, msg_id)
        except Exception:
            logger.exception("消息处理失败 %s，不 ACK（留 pending 重投）", msg_id)

    # ---------- RabbitMQ ----------
    async def _run_rabbit(self) -> None:
        import aio_pika

        s = self.settings
        connection = await aio_pika.connect_robust(s.rabbit_url)
        async with connection:
            channel = await connection.channel()
            await channel.set_qos(prefetch_count=1)
            exchange = await channel.declare_exchange("prism.kb.direct", aio_pika.ExchangeType.DIRECT, durable=True)
            queue = await channel.declare_queue("prism.kb.task", durable=True)
            await queue.bind(exchange, routing_key="kb.task")
            logger.info("RabbitMQ 消费启动: queue=%s", "prism.kb.task")

            async def on_message(message: aio_pika.IncomingMessage) -> None:
                async with message.process(requeue=False, ignore_processed=True):
                    try:
                        payload = json.loads(message.body.decode("utf-8"))
                        await self.pipeline.handle_message(
                            int(payload["docId"]), int(payload["kbId"])
                        )
                    except Exception:
                        logger.exception("Rabbit 消息处理失败，requeue 重投")
                        await asyncio.sleep(2)  # 避免热循环
                        await message.nack(requeue=True)

            await queue.consume(on_message)
            await asyncio.Future()  # 常驻


def _extract_payload(fields: dict) -> dict:
    """兼容两种投递形态：{payload: json字符串} 或直接 {docId, kbId} 字段。"""
    raw = fields.get("payload") or fields.get("body")
    if raw:
        return json.loads(raw)
    return {k: v for k, v in fields.items()}


_pipeline: ParsePipeline | None = None


def get_pipeline() -> ParsePipeline:
    """进程级单例（main lifespan 与 /internal/parse 共用）。"""
    global _pipeline
    if _pipeline is None:
        _pipeline = ParsePipeline()
    return _pipeline
