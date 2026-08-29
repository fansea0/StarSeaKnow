package com.fansea.ai.openapi.credential;

import java.util.Set;

public record RagKnowledgeScopeSnapshot(Set<Long> knowledgeIds) implements CredentialScopeSnapshot {

    public RagKnowledgeScopeSnapshot {
        knowledgeIds = Set.copyOf(knowledgeIds);
    }
}
