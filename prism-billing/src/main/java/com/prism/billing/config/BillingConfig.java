package com.prism.billing.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.prism.common.util.SnowFlakeId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * billing 通用配置：雪花 ID（worker-id=4，t_usage_record 主键非自增，消费端生成）、MP 分页插件。
 */
@Configuration
public class BillingConfig {

    @Bean
    @ConditionalOnMissingBean
    public SnowFlakeId snowFlakeId(@Value("${prism.worker-id:4}") long workerId) {
        return new SnowFlakeId(workerId);
    }

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
