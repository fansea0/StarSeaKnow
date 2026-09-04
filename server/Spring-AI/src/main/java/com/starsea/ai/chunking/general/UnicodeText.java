package com.starsea.ai.chunking.general;

import java.util.Objects;

/** Unicode code-point operations used by character-budget chunking. */
public final class UnicodeText {
    private UnicodeText() {}

    public static int length(String value) {
        if (value == null || value.isEmpty()) return 0;
        return value.codePointCount(0, value.length());
    }

    public static int charIndex(String value, int codePointOffset) {
        Objects.requireNonNull(value, "value");
        int length = length(value);
        if (codePointOffset < 0 || codePointOffset > length) {
            throw new IndexOutOfBoundsException("codePointOffset=" + codePointOffset + ", length=" + length);
        }
        return value.offsetByCodePoints(0, codePointOffset);
    }

    public static String substring(String value, int codePointStart, int codePointEnd) {
        Objects.requireNonNull(value, "value");
        if (codePointEnd < codePointStart) throw new IndexOutOfBoundsException("end before start");
        return value.substring(charIndex(value, codePointStart), charIndex(value, codePointEnd));
    }

    public static String prefix(String value, int maximumCodePoints) {
        return substring(value, 0, Math.min(length(value), Math.max(0, maximumCodePoints)));
    }
}
