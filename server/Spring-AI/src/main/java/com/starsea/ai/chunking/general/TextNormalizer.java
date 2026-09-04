package com.starsea.ai.chunking.general;

import com.starsea.ai.chunking.extraction.ExtractedText;
import com.starsea.ai.chunking.extraction.SourceSpan;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/** Applies unconditional newline and control-character normalization in one pass. */
@Component
public final class TextNormalizer {

    public NormalizedText normalize(String text) {
        return normalize(text, List.of());
    }

    public NormalizedText normalize(ExtractedText extracted) {
        if (extracted == null) throw new IllegalArgumentException("extracted text is required");
        return normalize(extracted.text(), extracted.sourceSpans());
    }

    private NormalizedText normalize(String input, List<SourceSpan> spans) {
        String source = input == null ? "" : input;
        StringBuilder output = new StringBuilder(source.length());
        int[] offsets = new int[source.length() + 1];
        int[] characterStarts = new int[source.length()];
        int[] characterEnds = new int[source.length()];
        int offsetCount = 1;
        int removed = 0;
        for (int index = 0; index < source.length();) {
            int codePoint = source.codePointAt(index);
            int width = Character.charCount(codePoint);
            if (codePoint == '\r') {
                int consumed = index + 1 < source.length() && source.charAt(index + 1) == '\n' ? 2 : 1;
                output.append('\n');
                characterStarts[output.length() - 1] = index;
                characterEnds[output.length() - 1] = index + consumed;
                offsets[offsetCount++] = index + consumed;
                index += consumed;
                continue;
            }
            if (Character.getType(codePoint) == Character.CONTROL && codePoint != '\n' && codePoint != '\t') {
                removed++;
                index += width;
                offsets[offsetCount - 1] = index;
                continue;
            }
            output.appendCodePoint(codePoint);
            int outputStart = output.length() - width;
            for (int outputIndex = outputStart; outputIndex < output.length(); outputIndex++) {
                characterStarts[outputIndex] = index;
                characterEnds[outputIndex] = index + width;
            }
            if (width == 2) offsets[offsetCount++] = index + 1;
            offsets[offsetCount++] = index + width;
            index += width;
        }
        return new NormalizedText(output.toString(), removed, Arrays.copyOf(offsets, offsetCount),
                Arrays.copyOf(characterStarts, output.length()),
                Arrays.copyOf(characterEnds, output.length()), spans);
    }
}
