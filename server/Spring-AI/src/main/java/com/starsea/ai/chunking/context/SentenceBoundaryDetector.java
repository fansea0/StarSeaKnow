package com.starsea.ai.chunking.context;

import java.util.ArrayList;
import java.util.List;

/** Finds complete Chinese or English sentences without interpreting source document syntax. */
public final class SentenceBoundaryDetector {

    public List<Sentence> completeSentences(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Sentence> sentences = new ArrayList<>();
        int start = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            int end = offset + Character.charCount(codePoint);
            if (isSentenceTerminator(codePoint) && !text.substring(start, end).isBlank()) {
                sentences.add(new Sentence(start, end));
                start = end;
            }
            offset = end;
        }
        return List.copyOf(sentences);
    }

    private boolean isSentenceTerminator(int codePoint) {
        return codePoint == '。' || codePoint == '！' || codePoint == '？'
                || codePoint == '.' || codePoint == '?' || codePoint == '!';
    }

    public record Sentence(int startOffset, int endOffset) {
    }
}
