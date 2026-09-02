package com.starsea.ai.model.provider;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

@Component
public class AesGcmModelProviderSecretCipher implements ModelProviderSecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int AUTHENTICATION_TAG_BITS = 128;
    private static final String DECRYPTION_FAILURE = "Unable to decrypt model provider secret";

    private final ModelProviderEncryptionProperties properties;
    private final SecureRandom secureRandom;

    public AesGcmModelProviderSecretCipher(ModelProviderEncryptionProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.secureRandom = new SecureRandom();
    }

    @Override
    public EncryptedProviderSecret encrypt(long tenantId, long providerId, String plaintext) {
        validateIdentity(tenantId, providerId);
        if (!StringUtils.hasText(plaintext)) {
            throw new IllegalArgumentException("Model provider secret must not be blank");
        }

        String keyVersion = properties.getActiveKeyVersion();
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, properties.requiredKey(keyVersion),
                    new GCMParameterSpec(AUTHENTICATION_TAG_BITS, nonce));
            cipher.updateAAD(additionalAuthenticatedData(tenantId, providerId, keyVersion));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new EncryptedProviderSecret(
                    Base64.getEncoder().encodeToString(ciphertext),
                    Base64.getEncoder().encodeToString(nonce),
                    keyVersion,
                    plaintext.substring(Math.max(0, plaintext.length() - 4)));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("Unable to encrypt model provider secret", exception);
        }
    }

    @Override
    public String decrypt(long tenantId, long providerId, EncryptedProviderSecret secret) {
        try {
            validateIdentity(tenantId, providerId);
            Objects.requireNonNull(secret, "secret");
            byte[] nonce = Base64.getDecoder().decode(secret.nonce());
            byte[] ciphertext = Base64.getDecoder().decode(secret.ciphertext());
            if (nonce.length != NONCE_BYTES || !StringUtils.hasText(secret.keyVersion())) {
                throw new IllegalArgumentException(DECRYPTION_FAILURE);
            }

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, properties.requiredKey(secret.keyVersion()),
                    new GCMParameterSpec(AUTHENTICATION_TAG_BITS, nonce));
            cipher.updateAAD(additionalAuthenticatedData(tenantId, providerId, secret.keyVersion()));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalArgumentException(DECRYPTION_FAILURE);
        }
    }

    private byte[] additionalAuthenticatedData(long tenantId, long providerId, String keyVersion) {
        return ("tenantId=" + tenantId
                + ";providerId=" + providerId
                + ";keyVersion=" + keyVersion).getBytes(StandardCharsets.UTF_8);
    }

    private void validateIdentity(long tenantId, long providerId) {
        if (tenantId <= 0 || providerId <= 0) {
            throw new IllegalArgumentException("Tenant and model provider IDs must be positive");
        }
    }
}
