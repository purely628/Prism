package com.prism.kb.config;

import com.prism.common.util.SnowFlakeId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KbConfig {

    @Bean
    public SnowFlakeId snowFlakeId(@Value("${prism.worker-id:5}") long workerId) {
        return new SnowFlakeId(workerId);
    }
}
