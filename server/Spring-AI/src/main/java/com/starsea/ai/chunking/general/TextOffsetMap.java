package com.starsea.ai.chunking.general;

import java.util.Arrays;
import java.util.Objects;

/**
 * Compact mapping from UTF-16 characters in one text to retained ranges in an upstream text.
 * Unchanged text uses an offset-only identity representation; changed text stores one packed
 * array of monotonic identity or constant ranges.
 */
final class TextOffsetMap {
    private static final int RANGE_WIDTH = 3;
    private static final int END = 0;
    private static final int SOURCE_START = 1;
    private static final int SOURCE_END = 2;

    private final String text;
    private final int identitySourceStart;
    private final int terminalSourceOffset;
    private final int[] ranges;
    private final int rangeCount;

    private TextOffsetMap(String text, int identitySourceStart, int terminalSourceOffset,
                          int[] ranges, int rangeCount) {
        this.text = Objects.requireNonNull(text, "text");
        this.identitySourceStart = identitySourceStart;
        this.terminalSourceOffset = terminalSourceOffset;
        this.ranges = ranges;
        this.rangeCount = rangeCount;
    }

    static TextOffsetMap identity(String text, int sourceStart) {
        Objects.requireNonNull(text, "text");
        return new TextOffsetMap(text, sourceStart, sourceStart + text.length(), null,
                text.isEmpty() ? 0 : 1);
    }

    static Builder builder() {
        return new Builder();
    }

    int textLength() {
        return text.length();
    }

    int sourceOffset(int utf16Offset) {
        if (utf16Offset < 0 || utf16Offset > text.length()) {
            throw new IndexOutOfBoundsException("utf16Offset=" + utf16Offset);
        }
        if (utf16Offset == text.length()) return terminalSourceOffset;
        if (ranges == null) return identitySourceStart + utf16Offset;
        int range = rangeContaining(utf16Offset);
        int outputStart = outputStart(range);
        int sourceStart = value(range, SOURCE_START);
        return isIdentity(range) ? sourceStart + utf16Offset - outputStart : sourceStart;
    }

    int characterStart(int utf16Index) {
        checkCharacterIndex(utf16Index);
        int characterStart = characterStartInText(utf16Index);
        if (ranges == null) return identitySourceStart + characterStart;
        int range = rangeContaining(characterStart);
        int sourceStart = value(range, SOURCE_START);
        return isIdentity(range) ? sourceStart + characterStart - outputStart(range) : sourceStart;
    }

    int characterEnd(int utf16Index) {
        checkCharacterIndex(utf16Index);
        int characterStart = characterStartInText(utf16Index);
        int characterEnd = characterStart + Character.charCount(text.codePointAt(characterStart));
        if (ranges == null) return identitySourceStart + characterEnd;
        int range = rangeContaining(characterStart);
        int sourceStart = value(range, SOURCE_START);
        return isIdentity(range) ? sourceStart + characterEnd - outputStart(range)
                : value(range, SOURCE_END);
    }

    boolean hasIdentityMapping() {
        return ranges == null
                && terminalSourceOffset == identitySourceStart + text.length();
    }

    int storageArrayCount() {
        return ranges == null ? 0 : 1;
    }

    int segmentCount() {
        return rangeCount;
    }

    void copyRangeTo(Builder target, int start, int end) {
        Objects.requireNonNull(target, "target");
        if (start < 0 || end < start || end > text.length()) {
            throw new IndexOutOfBoundsException("Invalid copied range");
        }
        if (start == end) return;
        if (ranges == null) {
            target.appendIdentity(end - start, identitySourceStart + start);
            return;
        }
        int range = rangeContaining(start);
        int cursor = start;
        while (cursor < end) {
            int outputStart = outputStart(range);
            int outputEnd = value(range, END);
            int copiedEnd = Math.min(end, outputEnd);
            int sourceStart = value(range, SOURCE_START);
            if (isIdentity(range)) {
                int copiedSourceStart = sourceStart + cursor - outputStart;
                target.appendIdentity(copiedEnd - cursor, copiedSourceStart);
            } else {
                target.appendConstant(copiedEnd - cursor, sourceStart,
                        value(range, SOURCE_END));
            }
            cursor = copiedEnd;
            range++;
        }
    }

    private void checkCharacterIndex(int utf16Index) {
        if (utf16Index < 0 || utf16Index >= text.length()) {
            throw new IndexOutOfBoundsException("utf16Index=" + utf16Index);
        }
    }

    private int characterStartInText(int utf16Index) {
        if (utf16Index > 0 && Character.isLowSurrogate(text.charAt(utf16Index))
                && Character.isHighSurrogate(text.charAt(utf16Index - 1))) {
            return utf16Index - 1;
        }
        return utf16Index;
    }

    private int rangeContaining(int utf16Index) {
        int low = 0;
        int high = rangeCount;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (value(middle, END) <= utf16Index) low = middle + 1;
            else high = middle;
        }
        if (low >= rangeCount) throw new IndexOutOfBoundsException("utf16Index=" + utf16Index);
        return low;
    }

    private int outputStart(int range) {
        return range == 0 ? 0 : value(range - 1, END);
    }

    private boolean isIdentity(int range) {
        return value(range, SOURCE_END) - value(range, SOURCE_START)
                == value(range, END) - outputStart(range);
    }

    private int value(int range, int field) {
        return ranges[range * RANGE_WIDTH + field];
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        return other instanceof TextOffsetMap map
                && identitySourceStart == map.identitySourceStart
                && terminalSourceOffset == map.terminalSourceOffset
                && rangeCount == map.rangeCount
                && text.equals(map.text)
                && Arrays.equals(ranges, map.ranges);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(text, identitySourceStart, terminalSourceOffset, rangeCount);
        return 31 * result + Arrays.hashCode(ranges);
    }

    static final class Builder {
        private int[] ranges;
        private int rangeCount;
        private int outputLength;

        void appendIdentity(int length, int sourceStart) {
            append(length, sourceStart, sourceStart + length);
        }

        void appendConstant(int length, int sourceStart, int sourceEnd) {
            append(length, sourceStart, sourceEnd);
        }

        TextOffsetMap build(String text) {
            int terminal = rangeCount == 0 ? 0 : ranges[(rangeCount - 1) * RANGE_WIDTH + SOURCE_END];
            return build(text, terminal);
        }

        TextOffsetMap build(String text, int terminalSourceOffset) {
            Objects.requireNonNull(text, "text");
            if (text.length() != outputLength) {
                throw new IllegalArgumentException("Mapped length does not match text length");
            }
            if (rangeCount == 0) {
                return new TextOffsetMap(text, terminalSourceOffset, terminalSourceOffset,
                        null, 0);
            }
            if (rangeCount == 1 && isIdentityRange(0)) {
                return new TextOffsetMap(text, ranges[SOURCE_START], terminalSourceOffset,
                        null, 1);
            }
            int[] ownedRanges = ranges;
            ranges = null;
            int ownedRangeCount = rangeCount;
            rangeCount = 0;
            outputLength = 0;
            return new TextOffsetMap(text, 0, terminalSourceOffset, ownedRanges,
                    ownedRangeCount);
        }

        private void append(int length, int sourceStart, int sourceEnd) {
            if (length < 0 || sourceEnd < sourceStart) {
                throw new IllegalArgumentException("Invalid mapped range");
            }
            if (length == 0) return;
            if (canMerge(length, sourceStart, sourceEnd)) {
                int base = (rangeCount - 1) * RANGE_WIDTH;
                outputLength += length;
                ranges[base + END] = outputLength;
                ranges[base + SOURCE_END] = sourceEnd;
                return;
            }
            ensureRangeCapacity(rangeCount + 1);
            int base = rangeCount * RANGE_WIDTH;
            outputLength += length;
            ranges[base + END] = outputLength;
            ranges[base + SOURCE_START] = sourceStart;
            ranges[base + SOURCE_END] = sourceEnd;
            rangeCount++;
        }

        private boolean canMerge(int length, int sourceStart, int sourceEnd) {
            if (rangeCount == 0) return false;
            int previous = rangeCount - 1;
            int previousBase = previous * RANGE_WIDTH;
            int previousSourceStart = ranges[previousBase + SOURCE_START];
            int previousSourceEnd = ranges[previousBase + SOURCE_END];
            boolean previousIdentity = isIdentityRange(previous);
            boolean nextIdentity = sourceEnd - sourceStart == length;
            if (previousIdentity && nextIdentity) return previousSourceEnd == sourceStart;
            return !previousIdentity && !nextIdentity
                    && previousSourceStart == sourceStart && previousSourceEnd == sourceEnd;
        }

        private boolean isIdentityRange(int range) {
            int base = range * RANGE_WIDTH;
            int outputStart = range == 0 ? 0 : ranges[base - RANGE_WIDTH + END];
            return ranges[base + SOURCE_END] - ranges[base + SOURCE_START]
                    == ranges[base + END] - outputStart;
        }

        private void ensureRangeCapacity(int requiredRanges) {
            int required = requiredRanges * RANGE_WIDTH;
            if (ranges == null) {
                ranges = new int[Math.max(24, required)];
            } else if (required > ranges.length) {
                ranges = Arrays.copyOf(ranges, Math.max(required, ranges.length * 2));
            }
        }
    }
}
