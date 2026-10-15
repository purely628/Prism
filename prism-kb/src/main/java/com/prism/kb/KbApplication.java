package com.prism.kb;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 知识库编排服务（8104，MVC）：
 * 知识库/文档管理 → 解析任务派发（Redis Stream / RabbitMQ，Python 消费）→
 * 解析回调落库 → 检索与 RAG 对话代理（SSE 流式透传到 Python agent-service）。
 *
 * Java 管状态与编排（元数据/事务/任务状态机），Python 管模型生态（切分/embedding/向量库），
 * 跨语言用"MQ + 回调 + 状态机"解耦——企业里 AI 编排服务与业务中台的真实分工。
 */
@SpringBootApplication(scanBasePackages = "com.prism")
@MapperScan("com.prism.kb.mapper")
public class KbApplication {

    public static void main(String[] args) {
        SpringApplication.run(KbApplication.class, args);
    }
}
