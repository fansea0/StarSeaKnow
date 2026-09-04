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
        if (maximumCodePoints <= 0 || text.isEmpty()) {
            throw new IllegalArgumentException("maximumCodePoints must be positive");
        }
        int line = -1;
        int sentence = -1;
        int whitespace = -1;
        int lineCharEnd = -1;
        int sentenceCharEnd = -1;
        int whitespaceCharEnd = -1;
        int scanned = 0;
        int charOffset = 0;
        while (charOffset < text.length() && scanned < maximumCodePoints) {
            int codePoint = text.codePointAt(charOffset);
            charOffset += Character.charCount(codePoint);
            scanned++;
            if (codePoint == '\n') { line = scanned; lineCharEnd = charOffset; }
            if (isSentenceEnd(codePoint)) { sentence = scanned; sentenceCharEnd = charOffset; }
            if (UnicodeText.isWhitespace(codePoint)) {
                whitespace = scanned;
                whitespaceCharEnd = charOffset;
            }
        }
        if (scanned == 0) throw new IllegalArgumentException("maximumCodePoints must be positive");
        int end;
        int endChar;
        BoundaryKind kind;
        if (line > 0) {
            end = line;
            endChar = lineCharEnd;
            kind = BoundaryKind.LINE_BREAK;
        } else if (sentence > 0) {
            end = sentence;
            endChar = sentenceCharEnd;
            kind = BoundaryKind.SENTENCE_END;
        } else if (whitespace > 0) {
            end = whitespace;
            endChar = whitespaceCharEnd;
            kind = BoundaryKind.WHITESPACE;
        } else {
            end = scanned;
            endChar = charOffset;
            kind = BoundaryKind.FORCED_CHARACTER;
        }
        return new BoundaryUnit(text.substring(0, endChar), 0, end, sourceLocator, kind);
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
