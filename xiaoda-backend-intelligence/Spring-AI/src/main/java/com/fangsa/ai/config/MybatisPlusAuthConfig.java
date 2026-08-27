package com.fangsa.ai.config;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusInterceptorCustomizer;
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
    public MybatisPlusInterceptorCustomizer interceptorCustomizer(TenantLineInnerInterceptor tenant) {
        return mp -> {
            // 确保 tenant 拦截器是最后一个 inner interceptor
            mp.addInnerInterceptor(tenant);
        };
    }
}
