package com.starsea.ai.agent.execution;

public record ModelChunk(String text, TokenUsage usage, String finishReason) {
    public record TokenUsage(Long inputTokens, Long outputTokens, Long totalTokens) { }
    @Override public String toString() { return "ModelChunk[text=<redacted>, finishReason=" + finishReason + "]"; }
}
