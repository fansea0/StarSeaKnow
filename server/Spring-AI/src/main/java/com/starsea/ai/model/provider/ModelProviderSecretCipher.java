package com.starsea.ai.model.provider;

public interface ModelProviderSecretCipher {

    EncryptedProviderSecret encrypt(long tenantId, long providerId, String plaintext);

    String decrypt(long tenantId, long providerId, EncryptedProviderSecret secret);
}
