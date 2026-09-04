package com.starsea.ai.chunking.model;

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;

import java.util.Objects;

/** Validated configuration for delimiter-based, character-budget chunking. */
public record GeneralChunkConfig(
        String delimiter,
        DelimiterMode delimiterMode,
        int maxCharacters,
        boolean collapseWhitespace,
        boolean removeUrls,
        boolean removeEmails) implements ChunkStrategyConfig {

    public static final int MIN_CHARACTERS = 64;
    public static final int MAX_CHARACTERS = 4000;
    public static final int MAX_DELIMITER_CODE_POINTS = 256;

    public GeneralChunkConfig {
        if (delimiter == null || delimiter.isEmpty()) {
            throw new IllegalArgumentException("delimiter: 分隔符不能为空");
        }
        if (delimiter.codePointCount(0, delimiter.length()) > MAX_DELIMITER_CODE_POINTS) {
            throw new IllegalArgumentException("delimiter: delimiter must contain at most 256 Unicode code points");
        }
        Objects.requireNonNull(delimiterMode, "delimiterMode: delimiter mode is required");
        if (maxCharacters < MIN_CHARACTERS || maxCharacters > MAX_CHARACTERS) {
            throw new IllegalArgumentException("maxCharacters: maxCharacters must be between 64 and 4000");
        }
        if (delimiterMode == DelimiterMode.REGEX) {
            validateRegex(delimiter);
        }
    }

    public static GeneralChunkConfig defaults() {
        return new GeneralChunkConfig("\n", DelimiterMode.LITERAL, 500, true, false, false);
    }

    private static void validateRegex(String expression) {
        final Pattern pattern;
        try {
            pattern = Pattern.compile(expression);
        } catch (PatternSyntaxException exception) {
            throw new IllegalArgumentException("delimiter: 分隔符正则无效", exception);
        }
        for (String sample : new String[]{"", "a", "A1 _-\n中😀", expression}) {
            var matcher = pattern.matcher(sample);
            while (matcher.find()) {
                if (matcher.start() == matcher.end()) {
                    throw new IllegalArgumentException("delimiter: 分隔符正则不能产生零宽匹配");
                }
            }
        }
    }
}
