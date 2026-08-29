package com.fansea.ai.openapi.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "external-api")
public class ExternalApiTransportProperties {

    private boolean requireHttps;
    private List<String> trustedProxies = new ArrayList<>();
    private int authenticationFailureThreshold = 5;
    private long authenticationFailureBlockSeconds = 60;
    private long authenticationFailureMaximumSize = 20_000;
    private Duration retrievalTimeout = Duration.ofSeconds(10);
    private int retrievalExecutorThreads = 16;
    private int retrievalExecutorQueueCapacity = 64;

    public boolean isRequireHttps() {
        return requireHttps;
    }

    public void setRequireHttps(boolean requireHttps) {
        this.requireHttps = requireHttps;
    }

    public List<String> getTrustedProxies() {
        return List.copyOf(trustedProxies);
    }

    public void setTrustedProxies(List<String> trustedProxies) {
        this.trustedProxies = trustedProxies == null ? new ArrayList<>() : new ArrayList<>(trustedProxies);
    }

    public int getAuthenticationFailureThreshold() { return authenticationFailureThreshold; }
    public void setAuthenticationFailureThreshold(int value) { this.authenticationFailureThreshold = value; }
    public long getAuthenticationFailureBlockSeconds() { return authenticationFailureBlockSeconds; }
    public void setAuthenticationFailureBlockSeconds(long value) { this.authenticationFailureBlockSeconds = value; }
    public long getAuthenticationFailureMaximumSize() { return authenticationFailureMaximumSize; }
    public void setAuthenticationFailureMaximumSize(long value) { this.authenticationFailureMaximumSize = value; }
    public Duration getRetrievalTimeout() { return retrievalTimeout; }
    public void setRetrievalTimeout(Duration value) { this.retrievalTimeout = value; }
    public int getRetrievalExecutorThreads() { return retrievalExecutorThreads; }
    public void setRetrievalExecutorThreads(int value) { this.retrievalExecutorThreads = value; }
    public int getRetrievalExecutorQueueCapacity() { return retrievalExecutorQueueCapacity; }
    public void setRetrievalExecutorQueueCapacity(int value) { this.retrievalExecutorQueueCapacity = value; }
}
