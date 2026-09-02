package com.starsea.ai.agent;

/** Metadata-only lifecycle event; consumers run after the deleting transaction commits. */
public record AgentDeletedEvent(long tenantId, long agentId) { }
