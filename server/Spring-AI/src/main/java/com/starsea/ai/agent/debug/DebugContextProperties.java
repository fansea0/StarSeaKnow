package com.starsea.ai.agent.debug;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "agent.debug")
public class DebugContextProperties {
    private int maxContexts = 1000;
    public int getMaxContexts() { return maxContexts; }
    public void setMaxContexts(int maxContexts) {
        if (maxContexts < 1 || maxContexts > 10000) throw new IllegalArgumentException("agent.debug.max-contexts must be between 1 and 10000");
        this.maxContexts = maxContexts;
    }
}
