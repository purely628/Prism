# Prism 脱机演示手册

## 先说结论：数据不是写死的

| 数据 | 来源 | 在不在 git 里 |
|---|---|---|
| 演示渠道（mock + GLM 占位）、演示应用 API Key | `sql/init.sql` 种子 | ✅ 在仓库 |
| admin/admin123 账号 | user 服务首次启动自动播种（DataSeeder） | ✅ 逻辑在代码里 |
| 知识库/上传的文档/用量流水/账单 | **运行时产生的业务数据** | ❌ 不在（纯运行产物） |
| 上传的原始文件 | `data/kb/`（.gitignore 已排除） | ❌ 不在 |
| 向量库 | `agent-service/data/chroma/`（gitignore） | ❌ 不在 |

所以**任何一台新机器 clone 下来就是一个能跑的空平台**：登录 admin/admin123、建应用拿 Key、调 /v1 接口（mock 渠道）全部开箱即用；要演示 RAG 就现场传一个文档（2 分钟内出 READY）。

**脱机（断网）能力**：mock LLM + 本地哈希 embedding + 本地 Chroma + 本地 MySQL/Redis——**运行期零外网依赖**。需要互联网的只有两处：首次构建时拉依赖（Maven/npm/pip）、给 GLM 渠道填真实 key 后调真实模型。

## 场景 A：在自己电脑上重复演示（重置）

演示数据玩脏了，恢复出厂三步（MySQL 记得带 utf8mb4，否则中文比较失配会误删演示应用）：

```bash
# 1. 重置数据库（保留演示应用/渠道/admin，清掉业务数据）
mysql -h127.0.0.1 -uroot -p --default-character-set=utf8mb4 < sql/reset-demo.sql

# 2. 清运行文件与向量（agent-service 要先停，Windows 文件锁）
rm -rf data/kb agent-service/data/chroma

# 3. 清 Redis 运行态（Windows 下 --scan 管道不可靠且输出带 \r，用 KEYS + tr）
/d/develop/redis/redis-cli.exe KEYS "prism:*" | tr -d '\r' | grep -v "^prism:app:" | xargs -r /d/develop/redis/redis-cli.exe DEL
/d/develop/redis/redis-cli.exe KEYS "prism:app:*" | tr -d '\r' | xargs -r /d/develop/redis/redis-cli.exe DEL
```

说明：app hash 全删是安全的——网关 miss 时会回源 user 服务自动重建（还能顺带清掉"库已删但 hash 还在"的孤儿应用）。
**必须重启 agent-service**（uvicorn 8200）：删向量库前要停它，删完必须拉起来——否则没人消费解析任务，上传的文档会永远卡在 PENDING（消息积压在 prism:stream:kb，重启后自动补处理）。其余 Java 服务不用重启，前端 Ctrl+F5 即是全新平台。

> 排查口诀：文档卡 PENDING = 解析任务没人消费。先 `curl http://127.0.0.1:8200/healthz` 看 agent-service 活没活，再 `redis-cli XLEN prism:stream:kb` 看积压。

## 场景 B：换一台电脑脱机演示

前置：JDK 17+、Maven、MySQL 8、Redis、Python 3.12、Node 20（首次装依赖需要网，之后断网可跑）。

```bash
git clone <你的仓库> && cd prism
mysql -h127.0.0.1 -uroot -p --default-character-set=utf8mb4 < sql/init.sql
mvn clean package -DskipTests

# 六个进程（或六个终端）
java -jar prism-user/target/prism-user-1.0.0.jar
java -jar prism-gateway/target/prism-gateway-1.0.0.jar
java -jar prism-chat/target/prism-chat-1.0.0.jar
java -jar prism-billing/target/prism-billing-1.0.0.jar
java -jar prism-kb/target/prism-kb-1.0.0.jar
cd agent-service && py -3.12 -m venv .venv && .venv/Scripts/pip install -r requirements.txt
.venv/Scripts/uvicorn app.main:app --port 8200

cd frontend && npm i && npm run dev     # http://localhost:5173  admin / admin123
```

**开讲前 2 分钟预热**（自动建知识库+传演示文档+等解析 READY+验证检索/问答，同时把看板数据喂出来）：

```bash
py docs/e2e_kb_flow.py    # 全部 ✓ 即预热完成，前端里直接能看到这个知识库
```

## 演示前 30 秒自检清单

```bash
for p in 8101 8080 8102 8103 8104 8200; do printf "%s " $p; curl -s -o /dev/null -w "%{http_code} " http://127.0.0.1:$p/api/kb; done; echo
curl -s -o /dev/null -w "前端 5173: %{http_code}\n" http://localhost:5173/
```

六个端口都有响应（401/404/500 都算"活着"，那是路径探测）+ 前端 200 → 可以开讲。

## 常见坑

- **vite 只监听 IPv6 的 localhost**：浏览器访问 `http://localhost:5173` 没问题，脚本压测别用 `127.0.0.1:5173`（会拒连），直连网关 8080 即可。
- **Git Bash curl 发中文 JSON 是 GBK**：命令行演示带中文 body 的接口，用 `py` + httpx，别用 curl -d。
- **检索测试空结果不是坏了**：0.15 噪声地板拦掉了字面不重叠的查询，换文档里实际出现的词（见前端空态提示）。
- **上传报"服务内部错误"**：确认用的是修复后的 request.js（FormData 会自动放行 Content-Type）。
