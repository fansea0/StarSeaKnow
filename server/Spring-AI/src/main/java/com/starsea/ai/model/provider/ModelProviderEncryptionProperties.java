package com.starsea.ai.model.provider;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "model-provider.encryption")
public class ModelProviderEncryptionProperties implements EnvironmentAware {

    public static final String DEVELOPMENT_DEFAULT_KEY =
            "ZGV2LW1vZGVsLXByb3ZpZGVyLWtleS12MS0zMmJ5dGU=";

    private String activeKeyVersion;
    private Map<String, String> keys = new LinkedHashMap<>();
    private Map<String, SecretKey> resolvedKeys = Map.of();
    private Environment environment = new StandardEnvironment();

    @PostConstruct
    public void afterPropertiesSet() {
        if (!StringUtils.hasText(activeKeyVersion) || !keys.containsKey(activeKeyVersion)) {
            throw new IllegalStateException(
                    "An active model provider encryption key version must be configured");
        }

        Map<String, SecretKey> validatedKeys = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : keys.entrySet()) {
            if (!StringUtils.hasText(entry.getKey()) || !StringUtils.hasText(entry.getValue())) {
                throw invalidKeyConfiguration();
            }
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(entry.getValue());
            } catch (IllegalArgumentException exception) {
                throw invalidKeyConfiguration();
            }
            if (decoded.length != 32) {
                throw invalidKeyConfiguration();
            }
            validatedKeys.put(entry.getKey(), new SecretKeySpec(decoded, "AES"));
        }

        if (environment.acceptsProfiles(Profiles.of("prod"))
                && usesDevelopmentDefault(activeKeyVersion)) {
            throw new IllegalStateException(
                    "The development model provider encryption key cannot be used in production");
        }
        resolvedKeys = Map.copyOf(validatedKeys);
    }

    public SecretKey requiredKey(String version) {
        SecretKey key = resolvedKeys.get(version);
        if (key == null) {
            throw new IllegalArgumentException("Unknown model provider encryption key version");
        }
        return key;
    }

    public boolean usesDevelopmentDefault(String version) {
        return DEVELOPMENT_DEFAULT_KEY.equals(keys.get(version));
    }

    private IllegalStateException invalidKeyConfiguration() {
        return new IllegalStateException(
                "Model provider encryption keys must be Base64 encoded 32-byte keys");
    }

    public String getActiveKeyVersion() {
        return activeKeyVersion;
    }

    public void setActiveKeyVersion(String activeKeyVersion) {
        this.activeKeyVersion = activeKeyVersion;
    }

    public void setKeys(Map<String, String> keys) {
        this.keys = keys == null ? new LinkedHashMap<>() : new LinkedHashMap<>(keys);
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment == null ? new StandardEnvironment() : environment;
    }
}
