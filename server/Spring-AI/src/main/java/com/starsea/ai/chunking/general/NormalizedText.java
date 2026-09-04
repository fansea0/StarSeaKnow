package com.starsea.ai.chunking.general;

import com.starsea.ai.chunking.extraction.SourceSpan;
import com.starsea.ai.chunking.model.SourceLocator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Normalized text plus a monotonic map back to extracted-text UTF-16 offsets. */
public final class NormalizedText {
    private final String text;
    private final int controlCharactersRemoved;
    private final int[] originalOffsets;
    private final int[] originalCharacterStarts;
    private final int[] originalCharacterEnds;
    private final List<SourceSpan> sourceSpans;

    NormalizedText(String text, int controlCharactersRemoved, int[] originalOffsets,
                   int[] originalCharacterStarts, int[] originalCharacterEnds,
                   List<SourceSpan> sourceSpans) {
        this.text = text;
        this.controlCharactersRemoved = controlCharactersRemoved;
        this.originalOffsets = originalOffsets.clone();
        this.originalCharacterStarts = originalCharacterStarts.clone();
        this.originalCharacterEnds = originalCharacterEnds.clone();
        this.sourceSpans = sourceSpans == null ? List.of() : sourceSpans.stream()
                .sorted(Comparator.comparingInt(SourceSpan::textStart))
                .toList();
    }

    public String text() { return text; }
    public int controlCharactersRemoved() { return controlCharactersRemoved; }
    public int codePointCount() { return UnicodeText.length(text); }

    public int originalOffset(int normalizedUtf16Offset) {
        if (normalizedUtf16Offset < 0 || normalizedUtf16Offset >= originalOffsets.length) {
            throw new IndexOutOfBoundsException("normalizedUtf16Offset=" + normalizedUtf16Offset);
        }
        return originalOffsets[normalizedUtf16Offset];
    }

    public SourceLocator sourceLocator(int normalizedStart, int normalizedEnd) {
        if (normalizedStart < 0 || normalizedEnd < normalizedStart || normalizedEnd > text.length()) {
            throw new IndexOutOfBoundsException("Invalid normalized range");
        }
        int originalStart = normalizedStart < normalizedEnd
                ? originalCharacterStarts[normalizedStart] : originalOffset(normalizedStart);
        int originalEnd = normalizedStart < normalizedEnd
                ? originalCharacterEnds[normalizedEnd - 1] : originalStart;
        List<Map<String, Object>> regions = new ArrayList<>();
        Integer startPage = null;
        Integer endPage = null;
        String type = "TEXT";
        int firstSpan = firstCandidateSpan(originalStart);
        for (int index = firstSpan; index < sourceSpans.size(); index++) {
            SourceSpan span = sourceSpans.get(index);
            if (span.textStart() >= originalEnd) break;
            if (span.textEnd() <= originalStart || span.textStart() >= originalEnd) continue;
            regions.add(span.source());
            Object page = span.source().get("page");
            if (page instanceof Number number) {
                int value = number.intValue();
                startPage = startPage == null ? value : Math.min(startPage, value);
                endPage = endPage == null ? value : Math.max(endPage, value);
                type = "PAGE";
            } else if (span.source().containsKey("sheet")) {
                type = "SHEET";
            } else if (span.source().containsKey("slide")) {
                type = "SLIDE";
            }
        }
        return new SourceLocator(type, List.of(), originalStart, originalEnd,
                null, null, startPage, endPage, regions);
    }

    private int firstCandidateSpan(int originalStart) {
        int low = 0;
        int high = sourceSpans.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (sourceSpans.get(middle).textEnd() <= originalStart) low = middle + 1;
            else high = middle;
        }
        return low;
    }
}
