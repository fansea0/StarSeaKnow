package com.starsea.ai.chunking.extraction;

public record ExtractionCapability(
        boolean available,
        String detectedMediaType,
        String extractorId,
        String extractorVersion,
        int priority,
        String reason) {

    public static ExtractionCapability unavailable(String mediaType, String reason) {
        return new ExtractionCapability(false, mediaType, null, null, Integer.MIN_VALUE, reason);
    }
}
