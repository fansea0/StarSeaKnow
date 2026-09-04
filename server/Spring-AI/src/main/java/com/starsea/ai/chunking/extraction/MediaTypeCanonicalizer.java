package com.starsea.ai.chunking.extraction;

import java.util.Locale;
import java.util.Objects;

/** Shared canonical form for extractor registration, probing and selection. */
final class MediaTypeCanonicalizer {

    private MediaTypeCanonicalizer() {
    }

    static String canonicalize(String value) {
        String mediaType = Objects.requireNonNull(value, "mediaType");
        int parameters = mediaType.indexOf(';');
        String base = parameters >= 0 ? mediaType.substring(0, parameters) : mediaType;
        return base.trim().toLowerCase(Locale.ROOT);
    }

    static boolean supports(DocumentTextExtractor extractor, String mediaType) {
        if (mediaType == null) return false;
        String canonicalMediaType = canonicalize(mediaType);
        return extractor.supportedMediaTypes().stream()
                .map(MediaTypeCanonicalizer::canonicalize)
                .anyMatch(canonicalMediaType::equals);
    }
}
