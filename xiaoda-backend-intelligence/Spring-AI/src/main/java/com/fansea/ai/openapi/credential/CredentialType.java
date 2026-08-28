package com.fansea.ai.openapi.credential;

import java.util.Arrays;

public enum CredentialType {
    RAG_RETRIEVAL("rag"),
    AGENT_INVOKE("agt");

    private final String prefix;

    CredentialType(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }

    public static CredentialType fromPrefix(String prefix) {
        return Arrays.stream(values())
                .filter(type -> type.prefix.equals(prefix))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Invalid API key prefix"));
    }
}
