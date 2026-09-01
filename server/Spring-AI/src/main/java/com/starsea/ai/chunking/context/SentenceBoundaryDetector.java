package com.starsea.ai.chunking.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Finds complete Chinese or English sentences without interpreting source document syntax. */
public final class SentenceBoundaryDetector {

    private static final Set<String> COMMON_ABBREVIATIONS = Set.of(
            "mr.", "mrs.", "ms.", "dr.", "prof.", "sr.", "jr.", "vs.", "etc.", "fig.", "no.");

    public List<Sentence> completeSentences(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Sentence> sentences = new ArrayList<>();
        int start = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            int end = offset + Character.charCount(codePoint);
            if (isSentenceTerminator(text, offset, codePoint) && !text.substring(start, end).isBlank()) {
                end = includeClosingPunctuation(text, end);
                sentences.add(new Sentence(start, end));
                start = end;
            }
            offset = end;
        }
        return List.copyOf(sentences);
    }

    private boolean isSentenceTerminator(String text, int offset, int codePoint) {
        if (codePoint == '。' || codePoint == '！' || codePoint == '？' || codePoint == '?' || codePoint == '!') {
            return true;
        }
        return codePoint == '.' && !isDecimalPoint(text, offset) && !isAbbreviationPoint(text, offset);
    }

    private int includeClosingPunctuation(String text, int offset) {
        int end = offset;
        while (end < text.length()) {
            int codePoint = text.codePointAt(end);
            if (!isClosingPunctuation(codePoint)) {
                break;
            }
            end += Character.charCount(codePoint);
        }
        return end;
    }

    private boolean isClosingPunctuation(int codePoint) {
        return switch (codePoint) {
            case '"', '\'', ')', ']', '}', '”', '’', '」', '』', '》', '〉', '〕', '】', '）', '］' -> true;
            default -> false;
        };
    }

    private boolean isDecimalPoint(String text, int offset) {
        return offset > 0 && offset + 1 < text.length()
                && Character.isDigit(text.charAt(offset - 1)) && Character.isDigit(text.charAt(offset + 1));
    }

    private boolean isAbbreviationPoint(String text, int offset) {
        int start = offset;
        while (start > 0 && isAsciiLetterOrDot(text.charAt(start - 1))) {
            start--;
        }
        int end = offset + 1;
        while (end < text.length() && isAsciiLetterOrDot(text.charAt(end))) {
            end++;
        }
        String word = text.substring(start, end).toLowerCase(Locale.ROOT);
        return word.matches("(?:[a-z]\\.){2,}") || COMMON_ABBREVIATIONS.contains(word);
    }

    private boolean isAsciiLetterOrDot(char value) {
        return value == '.' || (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z');
    }

    public record Sentence(int startOffset, int endOffset) {
    }
}
