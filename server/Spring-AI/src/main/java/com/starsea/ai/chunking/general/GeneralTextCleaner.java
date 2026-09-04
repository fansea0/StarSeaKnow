package com.starsea.ai.chunking.general;

import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import com.starsea.ai.chunking.model.GeneralChunkConfig;
import com.starsea.ai.chunking.model.SourceLocator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
            MappedText text = initialMapping(segment);
            if (config.removeUrls()) text = replace(text, URL, true, stats);
            if (config.removeEmails()) text = replace(text, EMAIL, false, stats);
            if (config.collapseWhitespace()) text = collapseWhitespace(text, stats);
            if (text.value().isEmpty()
                    || config.collapseWhitespace() && UnicodeText.isBlank(text.value())) {
                stats.empty++;
                continue;
            }
            int normalizedStart = text.offsets().characterStart(0);
            int normalizedEnd = text.offsets().characterEnd(text.value().length() - 1);
            CleanedOffsetMap offsetMap = normalized == null
                    ? CleanedOffsetMap.direct(text.offsets(), text.sourceDelta())
                    : CleanedOffsetMap.fromNormalized(text.offsets(), normalized);
            SourceLocator locator = normalized == null
                    ? locatorFromMapping(segment.sourceLocator(), offsetMap)
                    : normalized.sourceLocator(normalizedStart, normalizedEnd);
            cleaned.add(new CleanedSegment(text.value(), normalizedStart, normalizedEnd, locator,
                    segment.boundaryAfter(), offsetMap,
                    mappedRegions(normalized, normalizedStart, normalizedEnd, locator)));
        }
        return new CleaningResult(cleaned, stats.freeze());
    }

    private List<MappedSourceRegion> mappedRegions(NormalizedText normalized,
                                                    int normalizedStart, int normalizedEnd,
                                                    SourceLocator locator) {
        if (normalized != null) {
            return normalized.sourceRegions(normalizedStart, normalizedEnd);
        }
        if (locator == null || locator.startOffset() == null || locator.endOffset() == null) {
            return List.of();
        }
        return locator.regions().stream()
                .map(region -> new MappedSourceRegion(locator.startOffset(), locator.endOffset(), region))
                .toList();
    }

    private MappedText initialMapping(DelimitedSegment segment) {
        String text = segment.text();
        int sourceBase = segment.sourceLocator() != null && segment.sourceLocator().startOffset() != null
                ? segment.sourceLocator().startOffset() : segment.normalizedStart();
        return new MappedText(text, TextOffsetMap.identity(text, segment.normalizedStart()),
                sourceBase - segment.normalizedStart());
    }

    private MappedText replace(MappedText input, Pattern pattern, boolean url, MutableStats stats) {
        Matcher matcher = pattern.matcher(input.value());
        if (!matcher.find()) return input;
        MappedBuilder output = new MappedBuilder(input.value().length());
        int cursor = 0;
        do {
            output.copy(input, cursor, matcher.start());
            int characters = input.value().codePointCount(matcher.start(), matcher.end());
            if (url) {
                stats.urls++;
                stats.urlCharacters += characters;
            } else {
                stats.emails++;
                stats.emailCharacters += characters;
            }
            output.append(" ", 0, 1,
                    input.offsets().characterStart(matcher.start()),
                    input.offsets().characterEnd(matcher.end() - 1));
            cursor = matcher.end();
        } while (matcher.find());
        output.copy(input, cursor, input.value().length());
        return output.freeze(input.sourceDelta());
    }

    private MappedText collapseWhitespace(MappedText input, MutableStats stats) {
        int firstWhitespace = firstWhitespace(input.value());
        if (firstWhitespace < 0) return input;
        MappedBuilder output = new MappedBuilder(input.value().length());
        output.copy(input, 0, firstWhitespace);
        int cursor = firstWhitespace;
        while (cursor < input.value().length()) {
            int codePoint = input.value().codePointAt(cursor);
            if (!UnicodeText.isWhitespace(codePoint)) {
                int runStart = cursor;
                do {
                    cursor += Character.charCount(codePoint);
                    if (cursor >= input.value().length()) break;
                    codePoint = input.value().codePointAt(cursor);
                } while (!UnicodeText.isWhitespace(codePoint));
                output.copy(input, runStart, cursor);
                continue;
            }
            stats.whitespaceRuns++;
            int runStart = cursor;
            int runCodePoints = 0;
            int firstLineFeed = -1;
            int secondLineFeed = -1;
            while (cursor < input.value().length()) {
                int current = input.value().codePointAt(cursor);
                if (!UnicodeText.isWhitespace(current)) break;
                if (current == '\n') {
                    if (firstLineFeed < 0) firstLineFeed = cursor;
                    else if (secondLineFeed < 0) secondLineFeed = cursor;
                }
                runCodePoints++;
                cursor += Character.charCount(current);
            }
            int emitted = 0;
            if (firstLineFeed >= 0) {
                output.copy(input, firstLineFeed, firstLineFeed + 1);
                emitted++;
                if (secondLineFeed >= 0) {
                    output.copy(input, secondLineFeed, secondLineFeed + 1);
                    emitted++;
                }
            } else if (runStart > 0 && cursor < input.value().length()) {
                output.append(" ", 0, 1,
                        input.offsets().characterStart(runStart),
                        input.offsets().characterEnd(cursor - 1));
                emitted = 1;
            }
            stats.whitespaceRemoved += runCodePoints - emitted;
        }
        return output.freeze(input.sourceDelta());
    }

    private int firstWhitespace(String value) {
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (UnicodeText.isWhitespace(codePoint)) return offset;
            offset += Character.charCount(codePoint);
        }
        return -1;
    }

    private SourceLocator locatorFromMapping(SourceLocator source, CleanedOffsetMap offsets) {
        if (source == null) return null;
        return new SourceLocator(source.type(), source.blockIds(), offsets.sourceStart(0),
                offsets.sourceEnd(offsets.textLength()), source.startLine(), source.endLine(),
                source.startPage(), source.endPage(), source.regions());
    }

    private record MappedText(String value, TextOffsetMap offsets, int sourceDelta) {}

    private static final class MappedBuilder {
        private final StringBuilder value;
        private final TextOffsetMap.Builder offsets = TextOffsetMap.builder();

        private MappedBuilder(int capacity) {
            value = new StringBuilder(capacity);
        }

        private void copy(MappedText input, int start, int end) {
            if (start == end) return;
            value.append(input.value(), start, end);
            input.offsets().copyRangeTo(offsets, start, end);
        }

        private void append(String input, int start, int end,
                            int normalizedStart, int normalizedEnd) {
            value.append(input, start, end);
            offsets.appendConstant(end - start, normalizedStart, normalizedEnd);
        }

        private MappedText freeze(int sourceDelta) {
            String text = value.toString();
            return new MappedText(text, offsets.build(text), sourceDelta);
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
