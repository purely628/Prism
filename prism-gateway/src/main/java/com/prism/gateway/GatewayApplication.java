package com.prism.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * API 网关（Spring Cloud Gateway / WebFlux）。
 * 注意：不扫描 com.prism.common（其 GlobalExceptionHandler 依赖 servlet，网关是响应式栈），
 * 仅直接复用 common 中无 servlet 依赖的工具类（Result/ErrorCode/JwtUtil/RedisKeys）。
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
