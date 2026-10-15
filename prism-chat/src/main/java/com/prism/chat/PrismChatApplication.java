package com.prism.chat;

import com.prism.common.util.SnowFlakeId;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

/**
 * prism-chat 启动类（端口 8102，模型接入服务）。
 *
 * <p>组件扫描只扫 com.prism.chat，刻意不扫 com.prism.common：
 * common 里的 GlobalExceptionHandler 是 MVC（servlet 栈）注解组件，
 * 扫进 WebFlux 应用会引入 servlet 语义并使异常处理行为不可预期；
 * 所需公共类（UsageEvent / RedisKeys / MqConstants / BizException / SnowFlakeId）
 * 均为纯类，按类引用直接使用，不受包扫描影响。</p>
 */
@SpringBootApplication(scanBasePackages = "com.prism.chat")
@ConfigurationPropertiesScan(basePackages = "com.prism.chat")
@MapperScan("com.prism.chat.mapper")
public class PrismChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(PrismChatApplication.class, args);
    }

    /** 雪花 ID 机器位：chat 服务固定 3（契约约定，避免与 user/billing 实例冲突） */
    @Bean
    public SnowFlakeId snowFlakeId(com.prism.chat.config.ChatProperties props) {
        return new SnowFlakeId(props.getWorkerId());
    }
}
