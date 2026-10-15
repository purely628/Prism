package com.prism.billing;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 计量计费服务（8103）：
 * MQ 消费（默认 Redis Stream / rabbit 模式 RabbitMQ）→ 幂等 + 攒批入库 → Redis 配额累加；
 * 对账（DB 为准修正 Redis）、月度账单、用量查询接口。
 */
@SpringBootApplication(scanBasePackages = "com.prism")
@EnableScheduling
@MapperScan("com.prism.billing.mapper")
public class BillingApplication {

    public static void main(String[] args) {
        SpringApplication.run(BillingApplication.class, args);
    }
}
