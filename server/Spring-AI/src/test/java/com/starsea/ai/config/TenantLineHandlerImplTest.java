package com.starsea.ai.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TenantLineHandlerImplTest {

    private final TenantLineHandlerImpl handler = new TenantLineHandlerImpl();

    @Test
    void ignores_public_model_provider_catalog() {
        assertThat(handler.ignoreTable("model_provider_catalog")).isTrue();
        assertThat(handler.ignoreTable("MODEL_PROVIDER_CATALOG")).isTrue();
    }

    @Test
    void does_not_ignore_tenant_model_provider() {
        assertThat(handler.ignoreTable("tenant_model_provider")).isFalse();
    }
}
