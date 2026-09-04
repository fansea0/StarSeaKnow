package com.starsea.ai.chunking.general;

import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import com.starsea.ai.chunking.model.GeneralChunkConfig;
import com.starsea.ai.chunking.model.SourceLocator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Applies opt-in cleanup inside already-delimited segments. */
@Component
public final class GeneralTextCleaner {
    private static final Pattern URL = Pattern.compile(
            "(?i)\\b(?:https?://|www\\.)[^\\s<>\\\"']*[^\\s<>\\\"'.,;:!?)\\]}}]");
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");

    public CleaningResult clean(List<DelimitedSegment> segments, GeneralChunkConfig config) {
        return clean(segments, config, null);
    }

    public CleaningResult clean(List<DelimitedSegment> segments, GeneralChunkConfig config,
                                NormalizedText normalized) {
        Objects.requireNonNull(segments, "segments");
        Objects.requireNonNull(config, "config");
        MutableStats stats = new MutableStats();
        stats.controls = normalized == null ? 0 : normalized.controlCharactersRemoved();
        List<CleanedSegment> cleaned = new ArrayList<>();
        for (DelimitedSegment segment : segments) {
            MappedText text = initialMapping(segment, normalized);
            if (config.removeUrls()) text = replace(text, URL, true, stats);
            if (config.removeEmails()) text = replace(text, EMAIL, false, stats);
            if (config.collapseWhitespace()) text = collapseWhitespace(text, stats);
            if (text.value().isEmpty() || config.collapseWhitespace() && text.value().isBlank()) {
                stats.empty++;
                continue;
            }
            SourceLocator locator = normalized == null
                    ? locatorFromMapping(segment.sourceLocator(), text)
                    : normalized.sourceLocator(text.normalizedStarts()[0],
                            text.normalizedEnds()[text.value().length() - 1]);
            cleaned.add(new CleanedSegment(text.value(), text.normalizedStarts()[0],
                    text.normalizedEnds()[text.value().length() - 1], locator,
                    segment.boundaryAfter(), new CleanedOffsetMap(text.sourceStarts(), text.sourceEnds())));
        }
        return new CleaningResult(cleaned, stats.freeze());
    }

    private MappedText initialMapping(DelimitedSegment segment, NormalizedText normalized) {
        String text = segment.text();
        MappedBuilder output = new MappedBuilder(text.length());
        int sourceBase = segment.sourceLocator() != null && segment.sourceLocator().startOffset() != null
                ? segment.sourceLocator().startOffset() : segment.normalizedStart();
        for (int offset = 0; offset < text.length();) {
            int width = Character.charCount(text.codePointAt(offset));
            int normalizedStart = segment.normalizedStart() + offset;
            int normalizedEnd = normalizedStart + width;
            int sourceStart = normalized == null
                    ? sourceBase + offset : normalized.originalCharacterStart(normalizedStart);
            int sourceEnd = normalized == null
                    ? sourceBase + offset + width : normalized.originalCharacterEnd(normalizedEnd - 1);
            output.append(text, offset, offset + width,
                    normalizedStart, normalizedEnd, sourceStart, sourceEnd);
            offset += width;
        }
        return output.freeze();
    }

    private MappedText replace(MappedText input, Pattern pattern, boolean url, MutableStats stats) {
        Matcher matcher = pattern.matcher(input.value());
        MappedBuilder output = new MappedBuilder(input.value().length());
        int cursor = 0;
        while (matcher.find()) {
            output.copy(input, cursor, matcher.start());
            int characters = UnicodeText.length(matcher.group());
            if (url) {
                stats.urls++;
                stats.urlCharacters += characters;
            } else {
                stats.emails++;
                stats.emailCharacters += characters;
            }
            output.append(" ", 0, 1,
                    input.normalizedStarts()[matcher.start()],
                    input.normalizedEnds()[matcher.end() - 1],
                    input.sourceStarts()[matcher.start()], input.sourceEnds()[matcher.end() - 1]);
            cursor = matcher.end();
        }
        output.copy(input, cursor, input.value().length());
        return output.freeze();
    }

    private MappedText collapseWhitespace(MappedText input, MutableStats stats) {
        MappedBuilder output = new MappedBuilder(input.value().length());
        int cursor = 0;
        while (cursor < input.value().length()) {
            int codePoint = input.value().codePointAt(cursor);
            int width = Character.charCount(codePoint);
            if (!isWhitespace(codePoint)) {
                output.copy(input, cursor, cursor + width);
                cursor += width;
                continue;
            }
            stats.whitespaceRuns++;
            int runStart = cursor;
            int runCodePoints = 0;
            List<Integer> lineFeedOffsets = new ArrayList<>(2);
            while (cursor < input.value().length()) {
                int current = input.value().codePointAt(cursor);
                if (!isWhitespace(current)) break;
                if (current == '\n' && lineFeedOffsets.size() < 2) lineFeedOffsets.add(cursor);
                runCodePoints++;
                cursor += Character.charCount(current);
            }
            int emitted = 0;
            if (!lineFeedOffsets.isEmpty()) {
                for (int lineFeedOffset : lineFeedOffsets) {
                    output.copy(input, lineFeedOffset, lineFeedOffset + 1);
                    emitted++;
                }
            } else if (runStart > 0 && cursor < input.value().length()) {
                output.append(" ", 0, 1,
                        input.normalizedStarts()[runStart], input.normalizedEnds()[cursor - 1],
                        input.sourceStarts()[runStart], input.sourceEnds()[cursor - 1]);
                emitted = 1;
            }
            stats.whitespaceRemoved += runCodePoints - emitted;
        }
        return output.freeze();
    }

    private boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private SourceLocator locatorFromMapping(SourceLocator source, MappedText text) {
        if (source == null) return null;
        return new SourceLocator(source.type(), source.blockIds(), text.sourceStarts()[0],
                text.sourceEnds()[text.value().length() - 1], source.startLine(), source.endLine(),
                source.startPage(), source.endPage(), source.regions());
    }

    private record MappedText(String value, int[] normalizedStarts, int[] normalizedEnds,
                              int[] sourceStarts, int[] sourceEnds) {}

    private static final class MappedBuilder {
        private final StringBuilder value;
        private int[] normalizedStarts;
        private int[] normalizedEnds;
        private int[] sourceStarts;
        private int[] sourceEnds;
        private int length;

        private MappedBuilder(int capacity) {
            value = new StringBuilder(capacity);
            normalizedStarts = new int[Math.max(1, capacity)];
            normalizedEnds = new int[Math.max(1, capacity)];
            sourceStarts = new int[Math.max(1, capacity)];
            sourceEnds = new int[Math.max(1, capacity)];
        }

        private void copy(MappedText input, int start, int end) {
            if (start == end) return;
            ensure(length + end - start);
            value.append(input.value(), start, end);
            System.arraycopy(input.normalizedStarts(), start, normalizedStarts, length, end - start);
            System.arraycopy(input.normalizedEnds(), start, normalizedEnds, length, end - start);
            System.arraycopy(input.sourceStarts(), start, sourceStarts, length, end - start);
            System.arraycopy(input.sourceEnds(), start, sourceEnds, length, end - start);
            length += end - start;
        }

        private void append(String input, int start, int end, int normalizedStart, int normalizedEnd,
                            int sourceStart, int sourceEnd) {
            ensure(length + end - start);
            value.append(input, start, end);
            for (int index = 0; index < end - start; index++) {
                normalizedStarts[length] = normalizedStart;
                normalizedEnds[length] = normalizedEnd;
                sourceStarts[length] = sourceStart;
                sourceEnds[length] = sourceEnd;
                length++;
            }
        }

        private void ensure(int required) {
            if (required <= normalizedStarts.length) return;
            int capacity = Math.max(required, normalizedStarts.length * 2);
            normalizedStarts = Arrays.copyOf(normalizedStarts, capacity);
            normalizedEnds = Arrays.copyOf(normalizedEnds, capacity);
            sourceStarts = Arrays.copyOf(sourceStarts, capacity);
            sourceEnds = Arrays.copyOf(sourceEnds, capacity);
        }

        private MappedText freeze() {
            return new MappedText(value.toString(), Arrays.copyOf(normalizedStarts, length),
                    Arrays.copyOf(normalizedEnds, length), Arrays.copyOf(sourceStarts, length),
                    Arrays.copyOf(sourceEnds, length));
        }
    }

    private static final class MutableStats {
        int urls;
        int urlCharacters;
        int emails;
        int emailCharacters;
        int whitespaceRuns;
        int whitespaceRemoved;
        int controls;
        int empty;

        CleaningStats freeze() {
            return new CleaningStats(urls, urlCharacters, emails, emailCharacters,
                    whitespaceRuns, whitespaceRemoved, controls, empty);
        }
    }
}
