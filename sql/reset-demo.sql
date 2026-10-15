-- ============================================================
-- Prism 演示数据重置脚本（恢复到出厂演示状态）
-- 清掉：测试期产生的知识库/文档/用量/账单等运行时数据
-- 保留：种子渠道、演示应用（sk-prism-demo0000...）、admin 账号
-- 注意：只对 MySQL。Redis 清理命令见 DEMO.md「重置」一节。
-- ============================================================
USE prism;

-- 运行时业务数据（全部可安全清空，重启后由演示流程重新生成）
TRUNCATE TABLE t_kb;
TRUNCATE TABLE t_document;
TRUNCATE TABLE t_doc_chunk;
TRUNCATE TABLE t_usage_record;
TRUNCATE TABLE t_bill;
TRUNCATE TABLE t_reconcile_diff;
TRUNCATE TABLE t_failed_message;

-- 应用：只保留演示应用，删掉测试期创建的其它应用
DELETE FROM t_app WHERE app_name <> '演示应用';

-- 本地文件与向量（在 shell 里执行，不在 SQL 中）：
--   rm -rf data/kb                （上传的原始文件）
--   rm -rf agent-service/data/chroma （向量库，会自动重建；先停 agent-service 再删，Windows 有文件锁）
--
-- Redis（在 shell 里执行；Windows 下 --scan 在管道里不可靠且输出带 \r，用 KEYS + tr）：
--   redis-cli KEYS "prism:*" | tr -d '\r' | grep -v "^prism:app:" | xargs -r redis-cli DEL
--   redis-cli KEYS "prism:app:*" | tr -d '\r' | xargs -r redis-cli DEL
-- 说明：app hash 全删是安全的——网关 miss 时会回源 user 服务 /internal/apps/bykey 自动重建，
--      这样也能顺带清掉"库已删但 hash 还在"的孤儿应用（否则网关仍会放行已删应用的 Key）。
