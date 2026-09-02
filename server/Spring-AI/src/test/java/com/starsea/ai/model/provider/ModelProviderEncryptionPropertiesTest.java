package com.starsea.ai.model.provider;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelProviderEncryptionPropertiesTest {

    private static final String VALID_KEY = encode("0123456789abcdef0123456789abcdef");

    @Test
    void rejects_missing_active_key_version() {
        ModelProviderEncryptionProperties properties = properties(null, Map.of("v1", VALID_KEY));

        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("An active model provider encryption key version must be configured");
    }

    @Test
    void rejects_active_version_without_a_matching_key() {
        ModelProviderEncryptionProperties properties = properties("v2", Map.of("v1", VALID_KEY));

        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("An active model provider encryption key version must be configured");
    }

    @Test
    void rejects_invalid_base64_key_without_exposing_its_value() {
        String invalid = "not-base64!";
        ModelProviderEncryptionProperties properties = properties("v1", Map.of("v1", invalid));

        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Model provider encryption keys must be Base64 encoded 32-byte keys")
                .hasMessageNotContaining(invalid);
    }

    @Test
    void rejects_key_that_is_not_exactly_32_bytes() {
        ModelProviderEncryptionProperties properties = properties(
                "v1", Map.of("v1", encode("too-short")));

        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Model provider encryption keys must be Base64 encoded 32-byte keys");
    }

    @Test
    void rejects_development_default_for_prod_profile() {
        ModelProviderEncryptionProperties properties = properties(
                "v1", Map.of("v1", ModelProviderEncryptionProperties.DEVELOPMENT_DEFAULT_KEY));
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");
        properties.setEnvironment(production);

        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The development model provider encryption key cannot be used in production");
    }

    @Test
    void returns_aes_key_for_a_valid_version() {
        ModelProviderEncryptionProperties properties = properties("v1", Map.of("v1", VALID_KEY));
        properties.afterPropertiesSet();

        SecretKey key = properties.requiredKey("v1");

        assertThat(key.getAlgorithm()).isEqualTo("AES");
        assertThat(key.getEncoded()).hasSize(32);
        assertThat(properties.getActiveKeyVersion()).isEqualTo("v1");
        assertThat(properties.usesDevelopmentDefault("v1")).isFalse();
    }

    @Test
    void rejects_unknown_key_version_at_use_time() {
        ModelProviderEncryptionProperties properties = properties("v1", Map.of("v1", VALID_KEY));
        properties.afterPropertiesSet();

        assertThatThrownBy(() -> properties.requiredKey("retired"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown model provider encryption key version");
    }

    private ModelProviderEncryptionProperties properties(String activeVersion, Map<String, String> keys) {
        ModelProviderEncryptionProperties properties = new ModelProviderEncryptionProperties();
        properties.setActiveKeyVersion(activeVersion);
        properties.setKeys(keys);
        properties.setEnvironment(new MockEnvironment());
        return properties;
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
