package com.prism.user.config;

import com.prism.common.util.SnowFlakeId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 通用 Bean：BCrypt 密码编码器（仅 spring-security-crypto，不引入安全过滤链）、雪花 ID
 */
@Configuration
public class UserCommonConfig {

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SnowFlakeId snowFlakeId(@Value("${prism.worker-id:2}") long workerId) {
        return new SnowFlakeId(workerId);
    }
}
