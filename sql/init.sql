-- ============================================================
-- Prism · LLM 能力开放平台 初始化脚本
-- 库名：prism  字符集：utf8mb4
-- 说明：MySQL 8.x；user/billing/kb 三个服务共库不同表前缀
--      （演示环境单实例；生产建议按服务拆库，见 docs/ARCHITECTURE.md 演进说明）
-- ============================================================
CREATE DATABASE IF NOT EXISTS prism DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE prism;

-- ---------------- 用户表 ----------------
DROP TABLE IF EXISTS t_user;
CREATE TABLE t_user (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    username    VARCHAR(64)  NOT NULL COMMENT '登录名',
    password    VARCHAR(100) NOT NULL COMMENT 'BCrypt 密文',
    nickname    VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '昵称',
    role        VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT 'USER / ADMIN',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常 0禁用',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB COMMENT ='控制台用户';

-- ---------------- 应用（接入方）表 ----------------
DROP TABLE IF EXISTS t_app;
CREATE TABLE t_app (
    id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '应用ID',
    user_id       BIGINT        NOT NULL COMMENT '所属用户',
    app_name      VARCHAR(64)   NOT NULL COMMENT '应用名',
    app_key       VARCHAR(64)   NOT NULL COMMENT 'API Key：sk-prism- + 32位hex',
    monthly_quota BIGINT        NOT NULL DEFAULT 1000000 COMMENT '月 token 配额',
    rate_limit    INT           NOT NULL DEFAULT 10 COMMENT '每秒允许请求数（网关令牌桶速率）',
    status        TINYINT       NOT NULL DEFAULT 1 COMMENT '1启用 0禁用',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_key (app_key),
    KEY idx_user (user_id)
) ENGINE = InnoDB COMMENT ='接入应用';

-- ---------------- 模型渠道表 ----------------
DROP TABLE IF EXISTS t_model_channel;
CREATE TABLE t_model_channel (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '渠道ID',
    channel_name VARCHAR(64) NOT NULL COMMENT '渠道名',
    provider    VARCHAR(32)  NOT NULL COMMENT 'glm / deepseek / mock / openai-compatible',
    base_url    VARCHAR(255) NOT NULL COMMENT '上游地址；mock:// 前缀表示内置模拟渠道',
    api_key     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '上游密钥',
    model_name  VARCHAR(64)  NOT NULL COMMENT '上游真实模型名',
    alias       VARCHAR(64)  NOT NULL COMMENT '对外模型名（客户端请求里的 model 值）',
    weight      INT          NOT NULL DEFAULT 1 COMMENT '路由权重（同 alias 加权随机）',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0禁用',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_alias (alias)
) ENGINE = InnoDB COMMENT ='上游模型渠道';

-- ---------------- 用量流水表（billing 消费 MQ 攒批写入） ----------------
DROP TABLE IF EXISTS t_usage_record;
CREATE TABLE t_usage_record (
    id                BIGINT      NOT NULL COMMENT '雪花ID（消费端生成）',
    msg_id            VARCHAR(32) NOT NULL COMMENT '消息幂等键',
    app_id            BIGINT      NOT NULL COMMENT '应用ID',
    model             VARCHAR(64) NOT NULL COMMENT '模型别名',
    channel_id        BIGINT      NULL COMMENT '渠道ID',
    prompt_tokens     INT         NOT NULL DEFAULT 0,
    completion_tokens INT         NOT NULL DEFAULT 0,
    total_tokens      INT         NOT NULL DEFAULT 0,
    latency_ms        BIGINT      NOT NULL DEFAULT 0,
    cache_hit         TINYINT     NOT NULL DEFAULT 0 COMMENT '是否命中语义缓存',
    created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '流水时间（事件发生时间）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_msg_id (msg_id),
    KEY idx_app_time (app_id, created_at),
    KEY idx_time (created_at)
) ENGINE = InnoDB COMMENT ='推理用量流水';

-- ---------------- 月度账单表 ----------------
DROP TABLE IF EXISTS t_bill;
CREATE TABLE t_bill (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    app_id        BIGINT      NOT NULL,
    bill_month    CHAR(7)     NOT NULL COMMENT 'yyyy-MM',
    total_tokens  BIGINT      NOT NULL DEFAULT 0,
    call_count    BIGINT      NOT NULL DEFAULT 0,
    amount        DECIMAL(12,4) NOT NULL DEFAULT 0 COMMENT '模拟单价计费（元）：prompt 0.002/1k，completion 0.004/1k',
    status        TINYINT     NOT NULL DEFAULT 0 COMMENT '0待确认 1已出账',
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_month (app_id, bill_month)
) ENGINE = InnoDB COMMENT ='月度账单';

-- ---------------- 对账差异表 ----------------
DROP TABLE IF EXISTS t_reconcile_diff;
CREATE TABLE t_reconcile_diff (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    reconcile_day DATE        NOT NULL,
    app_id        BIGINT      NOT NULL,
    db_tokens     BIGINT      NOT NULL DEFAULT 0 COMMENT 'MySQL 流水汇总',
    redis_tokens  BIGINT      NOT NULL DEFAULT 0 COMMENT 'Redis 计数器',
    diff          BIGINT      NOT NULL DEFAULT 0,
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_day (reconcile_day)
) ENGINE = InnoDB COMMENT ='对账差异记录';

-- ---------------- 死信/失败消息表 ----------------
DROP TABLE IF EXISTS t_failed_message;
CREATE TABLE t_failed_message (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    msg_id      VARCHAR(32)  NOT NULL,
    payload     TEXT         NOT NULL COMMENT '原始 JSON',
    error       VARCHAR(500) NOT NULL DEFAULT '',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE = InnoDB COMMENT ='消费失败落库（死信兜底）';

-- ---------------- 知识库表 ----------------
DROP TABLE IF EXISTS t_kb;
CREATE TABLE t_kb (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    user_id         BIGINT      NOT NULL COMMENT '所属用户',
    kb_name         VARCHAR(64) NOT NULL COMMENT '知识库名',
    description     VARCHAR(255) NOT NULL DEFAULT '',
    chunk_size      INT         NOT NULL DEFAULT 500 COMMENT '切分块字符数',
    chunk_overlap   INT         NOT NULL DEFAULT 50 COMMENT '切分重叠字符数',
    top_k           INT         NOT NULL DEFAULT 4 COMMENT '检索召回条数',
    status          TINYINT     NOT NULL DEFAULT 1,
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user (user_id)
) ENGINE = InnoDB COMMENT ='RAG 知识库';

-- ---------------- 文档表 ----------------
DROP TABLE IF EXISTS t_document;
CREATE TABLE t_document (
    id          BIGINT       NOT NULL COMMENT '雪花ID',
    kb_id       BIGINT       NOT NULL,
    doc_name    VARCHAR(255) NOT NULL,
    file_path   VARCHAR(500) NOT NULL COMMENT '本地存储相对路径 data/kb/{kbId}/{docId}/{原名}',
    file_size   BIGINT       NOT NULL DEFAULT 0,
    chunk_count INT          NOT NULL DEFAULT 0,
    status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PARSING/READY/FAILED',
    error       VARCHAR(500) NOT NULL DEFAULT '',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_kb (kb_id)
) ENGINE = InnoDB COMMENT ='知识库文档';

-- ---------------- 文档切块表（向量存 Chroma，原文与元数据存 MySQL） ----------------
DROP TABLE IF EXISTS t_doc_chunk;
CREATE TABLE t_doc_chunk (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    document_id BIGINT       NOT NULL,
    kb_id       BIGINT       NOT NULL,
    chunk_index INT          NOT NULL,
    content     TEXT         NOT NULL,
    token_count INT          NOT NULL DEFAULT 0,
    chroma_id   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT 'Chroma 中的向量ID',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_doc (document_id),
    KEY idx_kb (kb_id)
) ENGINE = InnoDB COMMENT ='文档切块元数据';

-- ---------------- 种子数据 ----------------
-- 控制台账号 admin/admin123 不在此插入：BCrypt 哈希由 prism-user
-- 首次启动的 ApplicationRunner 生成（见 user 服务 DataSeeder），避免假哈希。

-- 内置模拟渠道：离线即可演示；GLM 渠道预留（api_key 留空表示未启用，可在控制台补）
INSERT INTO t_model_channel (channel_name, provider, base_url, api_key, model_name, alias, weight, status) VALUES
('内置模拟渠道', 'mock', 'mock://local', '', 'demo-mock', 'prism-demo', 5, 1),
('GLM-4-Flash', 'glm', 'https://open.bigmodel.cn/api/paas/v4', '', 'glm-4-flash', 'glm-4-flash', 5, 1);

-- 演示应用：Key = sk-prism-demo0000demo0000demo0000demo0000
INSERT INTO t_app (user_id, app_name, app_key, monthly_quota, rate_limit, status) VALUES
(1, '演示应用', 'sk-prism-demo0000demo0000demo0000demo0000', 1000000, 10, 1);
