package com.starsea.ai.chunking.general;

import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import com.starsea.ai.chunking.model.GeneralChunkConfig;
import com.starsea.ai.chunking.model.SourceLocator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Compiles one delimiter and consumes every match from normalized text. */
public final class GeneralBoundaryScanner {
    private final Pattern pattern;
    private boolean delimiterMatched;

    public GeneralBoundaryScanner(GeneralChunkConfig config) {
        this.pattern = Objects.requireNonNull(config, "config").compiledDelimiterPattern();
    }

    public List<DelimitedSegment> scan(NormalizedText normalized) {
        Objects.requireNonNull(normalized, "normalized");
        delimiterMatched = false;
        List<DelimitedSegment> result = new ArrayList<>();
        Matcher matcher = pattern.matcher(normalized.text());
        int start = 0;
        while (matcher.find()) {
            if (matcher.start() == matcher.end()) {
                throw new IllegalArgumentException("delimiter: 分隔符正则不能产生零宽匹配");
            }
            delimiterMatched = true;
            result.add(segment(normalized, start, matcher.start(), BoundaryKind.USER_DELIMITER));
            start = matcher.end();
        }
        result.add(segment(normalized, start, normalized.text().length(), null));
        return List.copyOf(result);
    }

    public boolean delimiterMatched() {
        return delimiterMatched;
    }

    /** Finds one preferred fallback boundary without consuming or changing any source character. */
    public static BoundaryUnit scanFallback(String text, int maximumCodePoints, SourceLocator sourceLocator) {
        Objects.requireNonNull(text, "text");
        int available = Math.min(UnicodeText.length(text), maximumCodePoints);
        if (available <= 0) throw new IllegalArgumentException("maximumCodePoints must be positive");
        int[] codePoints = text.codePoints().limit(available).toArray();
        int line = -1;
        int sentence = -1;
        int whitespace = -1;
        for (int index = 0; index < codePoints.length; index++) {
            int codePoint = codePoints[index];
            if (codePoint == '\n') line = index + 1;
            if (isSentenceEnd(codePoint)) sentence = index + 1;
            if (UnicodeText.isWhitespace(codePoint)) {
                whitespace = index + 1;
            }
        }
        int end;
        BoundaryKind kind;
        if (line > 0) {
            end = line;
            kind = BoundaryKind.LINE_BREAK;
        } else if (sentence > 0) {
            end = sentence;
            kind = BoundaryKind.SENTENCE_END;
        } else if (whitespace > 0) {
            end = whitespace;
            kind = BoundaryKind.WHITESPACE;
        } else {
            end = available;
            kind = BoundaryKind.FORCED_CHARACTER;
        }
        return new BoundaryUnit(UnicodeText.substring(text, 0, end), 0, end, sourceLocator, kind);
    }

    private static boolean isSentenceEnd(int codePoint) {
        return codePoint == '。' || codePoint == '！' || codePoint == '？'
                || codePoint == '.' || codePoint == '!' || codePoint == '?'
                || codePoint == '；' || codePoint == ';';
    }

    private DelimitedSegment segment(NormalizedText normalized, int start, int end, BoundaryKind boundary) {
        return new DelimitedSegment(normalized.text().substring(start, end), start, end,
                normalized.sourceLocator(start, end), boundary);
    }
}
