package com.starsea.ai.chunking.general;

import com.starsea.ai.chunking.extraction.ExtractedText;
import com.starsea.ai.chunking.extraction.SourceSpan;
import org.springframework.stereotype.Component;

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
        if (!requiresNormalization(source)) {
            return new NormalizedText(source, 0, TextOffsetMap.identity(source, 0), spans);
        }
        StringBuilder output = new StringBuilder(source.length());
        TextOffsetMap.Builder offsets = TextOffsetMap.builder();
        int removed = 0;
        for (int index = 0; index < source.length();) {
            int codePoint = source.codePointAt(index);
            int width = Character.charCount(codePoint);
            if (codePoint == '\r') {
                int consumed = index + 1 < source.length() && source.charAt(index + 1) == '\n' ? 2 : 1;
                output.append('\n');
                if (consumed == 1) offsets.appendIdentity(1, index);
                else offsets.appendConstant(1, index, index + consumed);
                index += consumed;
                continue;
            }
            if (Character.getType(codePoint) == Character.CONTROL && codePoint != '\n' && codePoint != '\t') {
                removed++;
                index += width;
                continue;
            }
            output.appendCodePoint(codePoint);
            offsets.appendIdentity(width, index);
            index += width;
        }
        String normalized = output.toString();
        return new NormalizedText(normalized, removed,
                offsets.build(normalized, source.length()), spans);
    }

    private boolean requiresNormalization(String source) {
        for (int index = 0; index < source.length();) {
            int codePoint = source.codePointAt(index);
            if (codePoint == '\r' || Character.getType(codePoint) == Character.CONTROL
                    && codePoint != '\n' && codePoint != '\t') {
                return true;
            }
            index += Character.charCount(codePoint);
        }
        return false;
    }
}
