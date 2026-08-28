package com.fansea.ai.openapi.credential;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

@Component
public class ApiKeyCodec {

    private static final int MAX_KEY_LENGTH = 256;
    private static final int KEY_ID_BYTES = 12;
    private static final int SECRET_BYTES = 32;
    private static final Pattern ENVIRONMENT = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
    private static final Pattern BASE64_URL = Pattern.compile("[A-Za-z0-9_-]+");

    private final ApiKeyProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public ApiKeyCodec(ApiKeyProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public IssuedKey issue(CredentialType type, String environment) {
        Objects.requireNonNull(type, "credential type");
        validateEnvironment(environment);
        String pepperVersion = properties.getActivePepperVersion();
        if ("live".equals(environment) && properties.usesDevelopmentDefault(pepperVersion)) {
            throw new IllegalStateException("The default pepper cannot issue live API keys");
        }

        String keyId = encode(randomBytes(KEY_ID_BYTES));
        String secret = encode(randomBytes(SECRET_BYTES));
        String rawPrefix = type.prefix() + "_" + environment;
        String rawKey = compose(rawPrefix, keyId, secret);
        String digest = digest(rawKey, requiredPepper(pepperVersion));
        return new IssuedKey(rawKey, keyId, secret, digest, pepperVersion, type, environment,
                rawPrefix + "_" + keyId, secret.substring(secret.length() - 4));
    }

    public ParsedKey parse(String rawKey) {
        if (rawKey == null || rawKey.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("Invalid API key");
        }
        int firstSeparator = rawKey.indexOf('_');
        int secondSeparator = rawKey.indexOf('_', firstSeparator + 1);
        int secretSeparator = rawKey.indexOf('.', secondSeparator + 1);
        if (firstSeparator < 1 || secondSeparator < firstSeparator + 2 || secretSeparator < secondSeparator + 2
                || rawKey.indexOf('.', secretSeparator + 1) >= 0) {
            throw new IllegalArgumentException("Invalid API key");
        }

        String typePrefix = rawKey.substring(0, firstSeparator);
        String environment = rawKey.substring(firstSeparator + 1, secondSeparator);
        String keyId = rawKey.substring(secondSeparator + 1, secretSeparator);
        String secret = rawKey.substring(secretSeparator + 1);
        CredentialType type = CredentialType.fromPrefix(typePrefix);
        validateEnvironment(environment);
        validateBase64Url(keyId);
        validateBase64Url(secret);
        if (Base64.getUrlDecoder().decode(keyId).length != KEY_ID_BYTES) {
            throw new IllegalArgumentException("Invalid API key");
        }
        if (Base64.getUrlDecoder().decode(secret).length != SECRET_BYTES) {
            throw new IllegalArgumentException("Invalid API key");
        }

        String rawPrefix = typePrefix + "_" + environment;
        if (!rawKey.equals(compose(rawPrefix, keyId, secret))) {
            throw new IllegalArgumentException("Invalid API key");
        }
        return new ParsedKey(rawPrefix, keyId, secret, type, environment);
    }

    public boolean verify(ParsedKey key, String digest, String pepperVersion) {
        if (key == null || digest == null || pepperVersion == null) {
            return false;
        }
        if (key.credentialType() == null || key.environment() == null
                || !(key.credentialType().prefix() + "_" + key.environment()).equals(key.rawPrefix())) {
            return false;
        }
        String pepper = properties.pepperFor(pepperVersion);
        if (pepper == null) {
            return false;
        }
        byte[] expected = digest(compose(key.rawPrefix(), key.keyId(), key.secret()), pepper)
                .getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, digest.getBytes(StandardCharsets.US_ASCII));
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        secureRandom.nextBytes(bytes);
        return bytes;
    }

    private String requiredPepper(String version) {
        String pepper = properties.pepperFor(version);
        if (pepper == null) {
            throw new IllegalStateException("Configured active API key pepper is missing");
        }
        return pepper;
    }

    private String digest(String rawKey, String pepper) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(rawKey.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to process API key", exception);
        }
    }

    private String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String compose(String rawPrefix, String keyId, String secret) {
        return rawPrefix + "_" + keyId + "." + secret;
    }

    private void validateEnvironment(String environment) {
        if (environment == null || !ENVIRONMENT.matcher(environment).matches()) {
            throw new IllegalArgumentException("Invalid API key environment");
        }
    }

    private void validateBase64Url(String value) {
        if (value == null || !BASE64_URL.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid API key");
        }
        try {
            Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid API key");
        }
    }

    public record IssuedKey(
            String rawKey, String keyId, String secret, String digest,
            String pepperVersion, CredentialType credentialType, String environment,
            String displayPrefix, String displayLastFour) {
    }

    public record ParsedKey(
            String rawPrefix, String keyId, String secret,
            CredentialType credentialType, String environment) {
    }
}
