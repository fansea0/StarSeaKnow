package com.starsea.ai.model.provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmModelProviderSecretCipherTest {

    private static final String PLAINTEXT = "sk-sensitive-test-value-1234";

    private AesGcmModelProviderSecretCipher cipher;

    @BeforeEach
    void setUp() {
        ModelProviderEncryptionProperties properties = new ModelProviderEncryptionProperties();
        properties.setActiveKeyVersion("v1");
        properties.setKeys(Map.of("v1", Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8))));
        properties.setEnvironment(new MockEnvironment());
        properties.afterPropertiesSet();
        cipher = new AesGcmModelProviderSecretCipher(properties);
    }

    @Test
    void encrypts_and_decrypts_for_the_same_tenant_and_provider() {
        EncryptedProviderSecret encrypted = cipher.encrypt(11L, 23L, PLAINTEXT);

        assertThat(cipher.decrypt(11L, 23L, encrypted)).isEqualTo(PLAINTEXT);
        assertThat(encrypted.keyVersion()).isEqualTo("v1");
        assertThat(encrypted.lastFour()).isEqualTo("1234");
        assertThat(encrypted.ciphertext()).doesNotContain(PLAINTEXT);
    }

    @Test
    void uses_a_fresh_nonce_for_each_encryption() {
        EncryptedProviderSecret first = cipher.encrypt(11L, 23L, PLAINTEXT);
        EncryptedProviderSecret second = cipher.encrypt(11L, 23L, PLAINTEXT);

        assertThat(first.nonce()).isNotEqualTo(second.nonce());
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
    }

    @Test
    void does_not_expose_ciphertext_or_nonce_when_logged() {
        EncryptedProviderSecret encrypted = cipher.encrypt(11L, 23L, PLAINTEXT);

        assertThat(encrypted.toString())
                .doesNotContain(encrypted.ciphertext())
                .doesNotContain(encrypted.nonce())
                .contains("keyVersion=v1", "lastFour=1234");
    }

    @Test
    void rejects_ciphertext_copied_to_another_tenant_or_provider() {
        EncryptedProviderSecret encrypted = cipher.encrypt(11L, 23L, PLAINTEXT);

        assertDecryptionFailsWithoutPlaintext(() -> cipher.decrypt(12L, 23L, encrypted));
        assertDecryptionFailsWithoutPlaintext(() -> cipher.decrypt(11L, 24L, encrypted));
    }

    @Test
    void rejects_tampered_ciphertext() {
        EncryptedProviderSecret encrypted = cipher.encrypt(11L, 23L, PLAINTEXT);
        byte[] tamperedBytes = Base64.getDecoder().decode(encrypted.ciphertext());
        tamperedBytes[0] ^= 1;
        EncryptedProviderSecret tampered = new EncryptedProviderSecret(
                Base64.getEncoder().encodeToString(tamperedBytes),
                encrypted.nonce(), encrypted.keyVersion(), encrypted.lastFour());

        assertDecryptionFailsWithoutPlaintext(() -> cipher.decrypt(11L, 23L, tampered));
    }

    @Test
    void rejects_unknown_key_version() {
        EncryptedProviderSecret encrypted = cipher.encrypt(11L, 23L, PLAINTEXT);
        EncryptedProviderSecret unknownVersion = new EncryptedProviderSecret(
                encrypted.ciphertext(), encrypted.nonce(), "retired", encrypted.lastFour());

        assertDecryptionFailsWithoutPlaintext(() -> cipher.decrypt(11L, 23L, unknownVersion));
    }

    @Test
    void rejects_invalid_inputs() {
        assertThatThrownBy(() -> cipher.encrypt(0L, 23L, PLAINTEXT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cipher.encrypt(11L, -1L, PLAINTEXT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cipher.encrypt(11L, 23L, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(PLAINTEXT);

        EncryptedProviderSecret malformed = new EncryptedProviderSecret(
                "not-base64!", "also-not-base64!", "v1", "1234");
        assertDecryptionFailsWithoutPlaintext(() -> cipher.decrypt(11L, 23L, malformed));
    }

    private void assertDecryptionFailsWithoutPlaintext(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unable to decrypt model provider secret")
                .hasMessageNotContaining(PLAINTEXT);
    }
}
