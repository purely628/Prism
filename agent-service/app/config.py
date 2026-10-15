"""pydantic-settings 配置：读取 CONTRACTS.md §7 的环境变量（单一事实来源）。

规则：
- 所有服务私有配置用 ``PRISM_`` 前缀（PRISM_LLM_BASE_URL / PRISM_KB_BASE ...）。
- ``MQ_MODE`` 是全平台共用变量（Java 与 Python 同名），不带头前缀（也接受 PRISM_MQ_MODE）。
- 默认值保证「离线可跑」：hash embedding + mock LLM + Redis Stream。
"""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from pydantic import AliasChoices, Field
from pydantic_settings import BaseSettings, SettingsConfigDict

_BASE_DIR = Path(__file__).resolve().parent.parent


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="PRISM_",
        env_file=str(_BASE_DIR / ".env"),
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # ---- LLM（默认 mock：不调任何外部 API）----
    llm_base_url: str = "mock"          # "mock" 或 OpenAI 兼容 base_url（如 https://open.bigmodel.cn/api/paas/v4）
    llm_api_key: str = ""
    llm_model: str = "prism-demo"
    llm_timeout: float = 120.0

    # ---- Embedding（默认 hash：零下载）----
    embed_provider: str = "hash"        # hash | text2vec
    text2vec_model: str = "shibing625/text2vec-base-chinese"

    # ---- kb 服务回调地址 ----
    kb_base: str = "http://127.0.0.1:8104"

    # ---- MQ（CONTRACTS §5：默认 Redis Stream）----
    mq_mode: str = Field(
        default="stream",
        validation_alias=AliasChoices("MQ_MODE", "mq_mode", "PRISM_MQ_MODE"),
    )  # stream | rabbit
    redis_url: str = "redis://127.0.0.1:6379/0"
    rabbit_url: str = "amqp://guest:guest@127.0.0.1:5672/%2f"
    kb_stream: str = "prism:stream:kb"  # 消费组固定 agent-service
    consumer_group: str = "agent-service"
    consumer_name: str = "agent-service-1"
    claim_idle_ms: int = 5 * 60 * 1000  # pending 超过 5 分钟即认领（简单故障恢复）

    # ---- 向量库 ----
    chroma_dir: str = str(_BASE_DIR / "data" / "chroma")

    # ---- RAG ----
    rag_score_threshold: float = 0.15   # 相似度低于该值的召回直接丢弃（可配）
    default_top_k: int = 4

    # ---- 服务 ----
    api_prefix: str = ""


@lru_cache
def get_settings() -> Settings:
    return Settings()
