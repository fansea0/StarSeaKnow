package com.fansea.ai.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisPlusAuthConfig {

    @Bean
    public TenantLineInnerInterceptor tenantLineInnerInterceptor(TenantLineHandler handler) {
        return new TenantLineInnerInterceptor(handler);
    }

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(TenantLineInnerInterceptor tenant) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(tenant);
        return interceptor;
    }
}
