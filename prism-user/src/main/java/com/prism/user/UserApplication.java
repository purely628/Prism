package com.prism.user;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 用户/应用/渠道服务（MVC）。
 * scanBasePackages=com.prism：同时装配 com.prism.user 与 com.prism.common 的
 * GlobalExceptionHandler / Result 等公共 MVC 组件。
 */
@SpringBootApplication(scanBasePackages = "com.prism")
@MapperScan("com.prism.user.mapper")
public class UserApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
    }
}
