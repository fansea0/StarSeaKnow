package com.starsea.ai.chunking.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;

import java.util.Objects;

/** Validated configuration for delimiter-based, character-budget chunking. */
public final class GeneralChunkConfig implements ChunkStrategyConfig {

    public static final int MIN_CHARACTERS = 64;
    public static final int MAX_CHARACTERS = 4000;
    public static final int MAX_DELIMITER_CODE_POINTS = 256;

    private final String delimiter;
    private final DelimiterMode delimiterMode;
    private final int maxCharacters;
    private final boolean collapseWhitespace;
    private final boolean removeUrls;
    private final boolean removeEmails;
    private final Pattern compiledDelimiterPattern;

    @JsonCreator
    public GeneralChunkConfig(
            @JsonProperty("delimiter") String delimiter,
            @JsonProperty("delimiterMode") DelimiterMode delimiterMode,
            @JsonProperty("maxCharacters") int maxCharacters,
            @JsonProperty("collapseWhitespace") boolean collapseWhitespace,
            @JsonProperty("removeUrls") boolean removeUrls,
            @JsonProperty("removeEmails") boolean removeEmails) {
        if (delimiter == null || delimiter.isEmpty()) {
            throw new IllegalArgumentException("delimiter: 分隔符不能为空");
        }
        String normalizedDelimiter = normalizeLineEndings(delimiter);
        if (delimiter.codePointCount(0, delimiter.length()) > MAX_DELIMITER_CODE_POINTS
                || normalizedDelimiter.codePointCount(0, normalizedDelimiter.length())
                > MAX_DELIMITER_CODE_POINTS) {
            throw new IllegalArgumentException(
                    "delimiter: delimiter must contain at most 256 Unicode code points");
        }
        this.delimiterMode = Objects.requireNonNull(
                delimiterMode, "delimiterMode: delimiter mode is required");
        if (maxCharacters < MIN_CHARACTERS || maxCharacters > MAX_CHARACTERS) {
            throw new IllegalArgumentException(
                    "maxCharacters: maxCharacters must be between 64 and 4000");
        }
        this.delimiter = normalizedDelimiter;
        this.maxCharacters = maxCharacters;
        this.collapseWhitespace = collapseWhitespace;
        this.removeUrls = removeUrls;
        this.removeEmails = removeEmails;
        this.compiledDelimiterPattern = compileDelimiterPattern(normalizedDelimiter, delimiterMode);
    }

    public static GeneralChunkConfig defaults() {
        return new GeneralChunkConfig("\n", DelimiterMode.LITERAL, 500, true, false, false);
    }

    @JsonProperty
    public String delimiter() {
        return delimiter;
    }

    @JsonProperty
    public DelimiterMode delimiterMode() {
        return delimiterMode;
    }

    @JsonProperty
    public int maxCharacters() {
        return maxCharacters;
    }

    @JsonProperty
    public boolean collapseWhitespace() {
        return collapseWhitespace;
    }

    @JsonProperty
    public boolean removeUrls() {
        return removeUrls;
    }

    @JsonProperty
    public boolean removeEmails() {
        return removeEmails;
    }

    /** Immutable request-scoped pattern compiled as part of validation and reused by scanning. */
    @JsonIgnore
    public Pattern compiledDelimiterPattern() {
        return compiledDelimiterPattern;
    }

    private static Pattern compileDelimiterPattern(String delimiter, DelimiterMode mode) {
        String expression = mode == DelimiterMode.LITERAL ? Pattern.quote(delimiter) : delimiter;
        final Pattern pattern;
        try {
            pattern = Pattern.compile(expression);
        } catch (PatternSyntaxException exception) {
            throw new IllegalArgumentException("delimiter: 分隔符正则无效", exception);
        }
        if (mode == DelimiterMode.REGEX) {
            validateNoZeroWidthMatches(pattern, delimiter);
        }
        return pattern;
    }

    private static String normalizeLineEndings(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static void validateNoZeroWidthMatches(Pattern pattern, String expression) {
        for (String sample : new String[]{"", "a", "A1 _-\n中😀", expression}) {
            var matcher = pattern.matcher(sample);
            while (matcher.find()) {
                if (matcher.start() == matcher.end()) {
                    throw new IllegalArgumentException("delimiter: 分隔符正则不能产生零宽匹配");
                }
            }
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof GeneralChunkConfig that)) return false;
        return maxCharacters == that.maxCharacters
                && collapseWhitespace == that.collapseWhitespace
                && removeUrls == that.removeUrls
                && removeEmails == that.removeEmails
                && delimiter.equals(that.delimiter)
                && delimiterMode == that.delimiterMode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(delimiter, delimiterMode, maxCharacters,
                collapseWhitespace, removeUrls, removeEmails);
    }

    @Override
    public String toString() {
        return "GeneralChunkConfig[delimiter=" + delimiter
                + ", delimiterMode=" + delimiterMode
                + ", maxCharacters=" + maxCharacters
                + ", collapseWhitespace=" + collapseWhitespace
                + ", removeUrls=" + removeUrls
                + ", removeEmails=" + removeEmails + ']';
    }
}
