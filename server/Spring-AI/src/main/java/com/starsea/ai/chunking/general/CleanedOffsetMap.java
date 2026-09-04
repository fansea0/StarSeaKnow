package com.starsea.ai.chunking.general;

import java.util.Arrays;

/** Maps each cleaned UTF-16 code unit to the exact retained source range. */
public final class CleanedOffsetMap {
    private final int[] sourceStarts;
    private final int[] sourceEnds;

    CleanedOffsetMap(int[] sourceStarts, int[] sourceEnds) {
        if (sourceStarts.length != sourceEnds.length) {
            throw new IllegalArgumentException("offset map arrays must have equal lengths");
        }
        this.sourceStarts = sourceStarts.clone();
        this.sourceEnds = sourceEnds.clone();
    }

    public int textLength() { return sourceStarts.length; }

    public int sourceStart(int cleanedUtf16Start) {
        if (cleanedUtf16Start < 0 || cleanedUtf16Start >= sourceStarts.length) {
            throw new IndexOutOfBoundsException("cleanedUtf16Start=" + cleanedUtf16Start);
        }
        return sourceStarts[cleanedUtf16Start];
    }

    public int sourceEnd(int cleanedUtf16End) {
        if (cleanedUtf16End <= 0 || cleanedUtf16End > sourceEnds.length) {
            throw new IndexOutOfBoundsException("cleanedUtf16End=" + cleanedUtf16End);
        }
        return sourceEnds[cleanedUtf16End - 1];
    }

    static CleanedOffsetMap identity(String text, int sourceStart) {
        int[] starts = new int[text.length()];
        int[] ends = new int[text.length()];
        for (int offset = 0; offset < text.length();) {
            int width = Character.charCount(text.codePointAt(offset));
            for (int index = offset; index < offset + width; index++) {
                starts[index] = sourceStart + offset;
                ends[index] = sourceStart + offset + width;
            }
            offset += width;
        }
        return new CleanedOffsetMap(starts, ends);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CleanedOffsetMap map
                && Arrays.equals(sourceStarts, map.sourceStarts)
                && Arrays.equals(sourceEnds, map.sourceEnds);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(sourceStarts) + Arrays.hashCode(sourceEnds);
    }
}
