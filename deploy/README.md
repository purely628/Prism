# Prism 部署（compose 一键拓扑）

## 前置

宿主机需要：JDK 17+、Maven、Node 20、Docker。

## 一键启动

```bash
# 1. 编译后端（compose 直接 COPY 各模块 fat jar）
mvn clean package -DskipTests

# 2. 构建前端产物（frontend 镜像内也会 npm build，这里只是预热缓存）
(cd frontend && npm i && npm run build)

# 3. 起全栈
docker compose -f deploy/docker-compose.yml up -d
```

## 服务地址

| 服务 | 地址 | 说明 |
|---|---|---|
| 控制台 | http://localhost | admin / admin123 |
| 网关 | http://localhost:8080 | OpenAI 兼容 /v1，控制台 /api |
| RabbitMQ 管理 | http://localhost:15672 | prism / prism123 |
| Nacos | http://localhost:8848/nacos | standalone |

## 常见问题

- **SSE 输出"整包一次性吐出"**：检查 nginx `proxy_buffering off` 与 `X-Accel-Buffering no`（deploy/nginx/nginx.conf 已配好）。
- **对话到 60 秒被掐断**：`proxy_read_timeout` 不足（已设 300s）。
- **chat 服务找不到渠道**：compose 里 RabbitMQ 未就绪会导致用量事件堆积，看 `docker compose logs prism-billing`。
- **Java 服务起不来**：确认 `mvn package` 已执行（compose 直接 COPY fat jar，不重复构建）。

## local vs compose

`local`（默认）：只依赖本机 MySQL + Redis，MQ 用 Redis Stream 实现，零 Docker 可跑通全链路——适合开发与演示。
`compose`：Nacos 注册发现 + RabbitMQ 真实消息队列 + Nginx 接入层限流，用 `SPRING_PROFILES_ACTIVE=compose` 切换。
