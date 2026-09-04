package com.starsea.ai.chunking.general;

import java.util.Objects;

/** Unicode code-point operations used by character-budget chunking. */
public final class UnicodeText {
    private UnicodeText() {}

    public static int length(String value) {
        if (value == null || value.isEmpty()) return 0;
        return value.codePointCount(0, value.length());
    }

    public static boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    public static boolean isBlank(CharSequence value) {
        if (value == null || value.length() == 0) return true;
        for (int offset = 0; offset < value.length();) {
            int codePoint = Character.codePointAt(value, offset);
            if (!isWhitespace(codePoint)) return false;
            offset += Character.charCount(codePoint);
        }
        return true;
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

    public static CodePointIndex index(String value) {
        return new CodePointIndex(value);
    }

    /** One-pass code-point boundary table for repeated monotonic slicing of a String. */
    public static final class CodePointIndex {
        private final String value;
        private final int[] charOffsets;
        private final int codePointLength;
        private final int scanOperations;

        private CodePointIndex(String value) {
            this.value = Objects.requireNonNull(value, "value");
            int firstSupplementary = firstSupplementaryOffset(value);
            if (firstSupplementary < 0) {
                this.charOffsets = null;
                this.codePointLength = value.length();
                this.scanOperations = codePointLength;
                return;
            }
            this.codePointLength = value.codePointCount(0, value.length());
            int[] offsets = new int[codePointLength + 1];
            int charOffset = 0;
            int codePointOffset = 0;
            while (charOffset < value.length()) {
                offsets[codePointOffset++] = charOffset;
                charOffset += Character.charCount(value.codePointAt(charOffset));
            }
            offsets[codePointOffset] = value.length();
            this.charOffsets = offsets;
            this.scanOperations = codePointOffset;
        }

        public int length() { return codePointLength; }
        public int scanOperations() { return scanOperations; }

        public int charIndex(int codePointOffset) {
            if (codePointOffset < 0 || codePointOffset > codePointLength) {
                throw new IndexOutOfBoundsException("codePointOffset=" + codePointOffset);
            }
            return charOffsets == null ? codePointOffset : charOffsets[codePointOffset];
        }

        public String substring(int codePointStart, int codePointEnd) {
            if (codePointEnd < codePointStart) throw new IndexOutOfBoundsException("end before start");
            return value.substring(charIndex(codePointStart), charIndex(codePointEnd));
        }

        int offsetTableArrayCount() {
            return charOffsets == null ? 0 : 1;
        }

        int offsetTableLength() {
            return charOffsets == null ? 0 : charOffsets.length;
        }

        private static int firstSupplementaryOffset(String value) {
            for (int index = 0; index + 1 < value.length(); index++) {
                if (Character.isHighSurrogate(value.charAt(index))
                        && Character.isLowSurrogate(value.charAt(index + 1))) {
                    return index;
                }
            }
            return -1;
        }
    }
}
