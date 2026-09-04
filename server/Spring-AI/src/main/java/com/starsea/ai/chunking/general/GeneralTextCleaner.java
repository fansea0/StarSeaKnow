package com.starsea.ai.chunking.general;

import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import com.starsea.ai.chunking.model.GeneralChunkConfig;
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
            String text = segment.text();
            if (config.removeUrls()) text = replace(text, URL, true, stats);
            if (config.removeEmails()) text = replace(text, EMAIL, false, stats);
            if (config.collapseWhitespace()) text = collapseWhitespace(text, stats);
            if (text.isEmpty()) {
                stats.empty++;
                continue;
            }
            cleaned.add(new CleanedSegment(text, segment.normalizedStart(), segment.normalizedEnd(),
                    segment.sourceLocator(), segment.boundaryAfter()));
        }
        return new CleaningResult(cleaned, stats.freeze());
    }

    private String replace(String input, Pattern pattern, boolean url, MutableStats stats) {
        Matcher matcher = pattern.matcher(input);
        StringBuffer output = new StringBuffer(input.length());
        while (matcher.find()) {
            int characters = UnicodeText.length(matcher.group());
            if (url) {
                stats.urls++;
                stats.urlCharacters += characters;
            } else {
                stats.emails++;
                stats.emailCharacters += characters;
            }
            matcher.appendReplacement(output, " ");
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private String collapseWhitespace(String input, MutableStats stats) {
        StringBuilder output = new StringBuilder(input.length());
        boolean inWhitespace = false;
        int runLength = 0;
        for (int offset = 0; offset < input.length();) {
            int codePoint = input.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                if (!inWhitespace) stats.whitespaceRuns++;
                inWhitespace = true;
                runLength++;
                continue;
            }
            if (inWhitespace && output.length() > 0) output.append(' ');
            inWhitespace = false;
            output.appendCodePoint(codePoint);
        }
        int resultWhitespace = countAsciiSpaces(output);
        stats.whitespaceRemoved += Math.max(0, runLength - resultWhitespace);
        return output.toString();
    }

    private int countAsciiSpaces(StringBuilder value) {
        int result = 0;
        for (int index = 0; index < value.length(); index++) if (value.charAt(index) == ' ') result++;
        return result;
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
