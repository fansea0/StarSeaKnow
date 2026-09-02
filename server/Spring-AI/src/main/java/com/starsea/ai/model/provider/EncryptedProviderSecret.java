package com.starsea.ai.model.provider;

public record EncryptedProviderSecret(
        String ciphertext,
        String nonce,
        String keyVersion,
        String lastFour) {

    @Override
    public String toString() {
        return "EncryptedProviderSecret[ciphertext=<redacted>, nonce=<redacted>, keyVersion="
                + keyVersion + ", lastFour=" + lastFour + "]";
    }
}
