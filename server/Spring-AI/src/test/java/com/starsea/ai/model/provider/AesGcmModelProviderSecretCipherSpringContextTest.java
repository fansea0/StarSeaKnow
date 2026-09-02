package com.starsea.ai.model.provider;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AesGcmModelProviderSecretCipherSpringContextTest {

    @Test
    void spring_constructs_cipher_with_encryption_properties() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ModelProviderEncryptionProperties.class, this::validProperties);
            context.register(AesGcmModelProviderSecretCipher.class);

            context.refresh();

            assertThat(context.getBean(ModelProviderSecretCipher.class))
                    .isInstanceOf(AesGcmModelProviderSecretCipher.class);
        }
    }

    private ModelProviderEncryptionProperties validProperties() {
        ModelProviderEncryptionProperties properties = new ModelProviderEncryptionProperties();
        properties.setActiveKeyVersion("v1");
        properties.setKeys(Map.of("v1", Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8))));
        return properties;
    }
}
