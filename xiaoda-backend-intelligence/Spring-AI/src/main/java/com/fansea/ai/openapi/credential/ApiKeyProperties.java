package com.fansea.ai.openapi.credential;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "external-api.api-key")
public class ApiKeyProperties {

    public static final String DEVELOPMENT_DEFAULT_PEPPER = "dev-only-rag-pepper-v1-change-me-32bytes";

    private String activePepperVersion;
    private Map<String, String> peppers = new LinkedHashMap<>();
    private Duration positiveCacheTtl = Duration.ofSeconds(60);
    private Duration negativeCacheTtl = Duration.ofSeconds(10);
    private long positiveCacheMaximumSize = 10_000;
    private long negativeCacheMaximumSize = 20_000;
    private int maxKnowledgeBases = 50;

    @PostConstruct
    public void afterPropertiesSet() {
        if (!StringUtils.hasText(activePepperVersion) || !peppers.containsKey(activePepperVersion)) {
            throw new IllegalStateException("An active API key pepper version must be configured");
        }
        for (Map.Entry<String, String> entry : peppers.entrySet()) {
            if (!StringUtils.hasText(entry.getKey()) || !StringUtils.hasText(entry.getValue())
                    || entry.getValue().getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalStateException("API key peppers must be at least 32 UTF-8 bytes");
            }
        }
    }

    public String getActivePepperVersion() {
        return activePepperVersion;
    }

    public void setActivePepperVersion(String activePepperVersion) {
        this.activePepperVersion = activePepperVersion;
    }

    public void setPeppers(Map<String, String> peppers) {
        this.peppers = peppers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(peppers);
    }

    public Duration getPositiveCacheTtl() {
        return positiveCacheTtl;
    }

    public void setPositiveCacheTtl(Duration positiveCacheTtl) {
        this.positiveCacheTtl = positiveCacheTtl;
    }

    public Duration getNegativeCacheTtl() {
        return negativeCacheTtl;
    }

    public void setNegativeCacheTtl(Duration negativeCacheTtl) {
        this.negativeCacheTtl = negativeCacheTtl;
    }

    public long getPositiveCacheMaximumSize() { return positiveCacheMaximumSize; }
    public void setPositiveCacheMaximumSize(long value) { this.positiveCacheMaximumSize = value; }
    public long getNegativeCacheMaximumSize() { return negativeCacheMaximumSize; }
    public void setNegativeCacheMaximumSize(long value) { this.negativeCacheMaximumSize = value; }
    public int getMaxKnowledgeBases() { return maxKnowledgeBases; }
    public void setMaxKnowledgeBases(int value) { this.maxKnowledgeBases = value; }

    public boolean usesDevelopmentDefault(String version) {
        return DEVELOPMENT_DEFAULT_PEPPER.equals(peppers.get(version));
    }

    String pepperFor(String version) {
        return peppers.get(version);
    }
}
