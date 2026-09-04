package com.starsea.ai.chunking.general;

import java.util.Objects;

/** Maps each cleaned UTF-16 character to the exact retained source range. */
public final class CleanedOffsetMap {
    private final TextOffsetMap cleanedToUpstream;
    private final TextOffsetMap normalizedToSource;
    private final int directSourceDelta;

    private CleanedOffsetMap(TextOffsetMap cleanedToUpstream,
                             TextOffsetMap normalizedToSource,
                             int directSourceDelta) {
        this.cleanedToUpstream = Objects.requireNonNull(cleanedToUpstream, "cleanedToUpstream");
        this.normalizedToSource = normalizedToSource;
        this.directSourceDelta = directSourceDelta;
    }

    static CleanedOffsetMap fromNormalized(TextOffsetMap cleanedToNormalized,
                                           NormalizedText normalized) {
        Objects.requireNonNull(normalized, "normalized");
        return new CleanedOffsetMap(cleanedToNormalized, normalized.offsetMapping(), 0);
    }

    static CleanedOffsetMap direct(TextOffsetMap cleanedToNormalized, int sourceDelta) {
        return new CleanedOffsetMap(cleanedToNormalized, null, sourceDelta);
    }

    public int textLength() {
        return cleanedToUpstream.textLength();
    }

    public int sourceStart(int cleanedUtf16Start) {
        int upstreamStart = cleanedToUpstream.characterStart(cleanedUtf16Start);
        return normalizedToSource == null
                ? upstreamStart + directSourceDelta
                : normalizedToSource.characterStart(upstreamStart);
    }

    public int sourceEnd(int cleanedUtf16End) {
        if (cleanedUtf16End <= 0 || cleanedUtf16End > textLength()) {
            throw new IndexOutOfBoundsException("cleanedUtf16End=" + cleanedUtf16End);
        }
        int upstreamEnd = cleanedToUpstream.characterEnd(cleanedUtf16End - 1);
        return normalizedToSource == null
                ? upstreamEnd + directSourceDelta
                : normalizedToSource.characterEnd(upstreamEnd - 1);
    }

    static CleanedOffsetMap identity(String text, int sourceStart) {
        return direct(TextOffsetMap.identity(text, sourceStart), 0);
    }

    boolean hasIdentityMapping() {
        return cleanedToUpstream.hasIdentityMapping();
    }

    boolean sharesSourceMapping(NormalizedText normalized) {
        return normalized != null && normalizedToSource == normalized.offsetMapping();
    }

    int mappingArrayCount() {
        return cleanedToUpstream.storageArrayCount();
    }

    int mappingSegmentCount() {
        return cleanedToUpstream.segmentCount();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        return other instanceof CleanedOffsetMap map
                && directSourceDelta == map.directSourceDelta
                && cleanedToUpstream.equals(map.cleanedToUpstream)
                && Objects.equals(normalizedToSource, map.normalizedToSource);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cleanedToUpstream, normalizedToSource, directSourceDelta);
    }
}
