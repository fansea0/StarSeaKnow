package com.fansea.ai.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MybatisPlusAuthConfigTest {

    @Test
    void registersPaginationInterceptorForTenantLists() {
        MybatisPlusAuthConfig config = new MybatisPlusAuthConfig();
        MybatisPlusInterceptor interceptor = config.mybatisPlusInterceptor(
                config.tenantLineInnerInterceptor(new TenantLineHandlerImpl()));

        assertTrue(interceptor.getInterceptors().stream()
                .anyMatch(PaginationInnerInterceptor.class::isInstance));
    }
}
