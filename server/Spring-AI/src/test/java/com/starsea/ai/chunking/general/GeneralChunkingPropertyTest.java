package com.starsea.ai.chunking.general;

import com.starsea.ai.chunking.context.ChunkIndexContentBuilder;
import com.starsea.ai.chunking.model.ChunkDraft;
import com.starsea.ai.chunking.model.ChunkPlanningRequest;
import com.starsea.ai.chunking.model.ChunkPlanningResult;
import com.starsea.ai.chunking.model.ContextConfig;
import com.starsea.ai.chunking.model.ContextMode;
import com.starsea.ai.chunking.model.DelimiterMode;
import com.starsea.ai.chunking.model.GeneralChunkConfig;
import com.starsea.ai.chunking.model.OverlapUnit;
import com.starsea.ai.chunking.model.ParsedStructure;
import com.starsea.ai.chunking.model.StructuredBlock;
import com.starsea.ai.chunking.spi.TokenCounter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneralChunkingPropertyTest {
    private static final String DELIMITER = "|||";
    private static final String URL = "https://drop.test";
    private static final String EMAIL = "user@example.com";

    @Test
    void generated_full_pipeline_inputs_are_lossless_bounded_mapped_and_deterministic() {
        Random random = new Random(0x5EA5EA);
        TokenCounter counter = codePointCounter();
        GeneralChunkPlanningStrategy planner = new GeneralChunkPlanningStrategy(counter);

        for (int sample = 0; sample < 160; sample++) {
            String source = generatedDocument(random);
            int maxCharacters = 64 + random.nextInt(65);
            boolean collapseWhitespace = random.nextBoolean();
            boolean removeUrls = random.nextBoolean();
            boolean removeEmails = random.nextBoolean();
            boolean overlapEnabled = random.nextBoolean();
            int overlapLimit = overlapEnabled ? 1 + random.nextInt(20) : 0;
            GeneralChunkConfig config = new GeneralChunkConfig(DELIMITER, DelimiterMode.LITERAL,
                    maxCharacters, collapseWhitespace, removeUrls, removeEmails);
            ContextConfig context = new ContextConfig(overlapEnabled, overlapLimit,
                    OverlapUnit.CHARACTERS, ContextMode.CHARACTER_TAIL);

            NormalizedText normalized = new TextNormalizer().normalize(source);
            GeneralBoundaryScanner scanner = new GeneralBoundaryScanner(config);
            CleaningResult cleaned = new GeneralTextCleaner().clean(scanner.scan(normalized), config, normalized);
            List<StructuredBlock> blocks = new ArrayList<>();
            for (int index = 0; index < cleaned.segments().size(); index++) {
                CleanedSegment segment = cleaned.segments().get(index);
                blocks.add(segment.toStructuredBlock("property-" + index, counter.count(segment.text())));
            }
            ChunkPlanningRequest request = new ChunkPlanningRequest(new ParsedStructure(null, blocks),
                    config, context, 512);

            ChunkPlanningResult first = planner.plan(request);
            ChunkPlanningResult second = planner.plan(request);
            String expected = String.join("\n", referenceCleanedSegments(source,
                    collapseWhitespace, removeUrls, removeEmails));

            assertTrue(scanner.delimiterMatched(), "sample " + sample);
            assertEquals(expected, reconstruct(first.drafts()), "sample " + sample);
            assertEquals(first, second, "sample " + sample);
            for (int index = 0; index < first.drafts().size(); index++) {
                ChunkDraft draft = first.drafts().get(index);
                int budget = index == 0 || !overlapEnabled
                        ? maxCharacters : maxCharacters - overlapLimit - 5;
                assertTrue(UnicodeText.length(draft.content()) <= budget, "sample " + sample);
                assertTrue(counter.count(ChunkIndexContentBuilder.preview(
                        draft.sectionPath(), draft.content())) <= 512, "sample " + sample);
                assertWellFormedUtf16(draft.content(), sample);
                assertFalse(draft.sourceLocator().startOffset() != null
                                && draft.sourceLocator().endOffset() != null
                                && draft.sourceLocator().startOffset() > draft.sourceLocator().endOffset(),
                        "sample " + sample);
            }
        }
    }

    @Test
    void emoji_split_uses_code_point_budget_instead_of_utf16_midpoint() {
        String source = "😀".repeat(65);
        GeneralChunkConfig config = new GeneralChunkConfig(DELIMITER, DelimiterMode.LITERAL,
                64, false, false, false);
        NormalizedText normalized = new TextNormalizer().normalize(source);
        CleanedSegment segment = new GeneralTextCleaner().clean(
                new GeneralBoundaryScanner(config).scan(normalized), config, normalized).segments().get(0);
        ChunkPlanningRequest request = new ChunkPlanningRequest(
                new ParsedStructure(null, List.of(segment.toStructuredBlock("emoji", 65))),
                config, new ContextConfig(false, 0, OverlapUnit.CHARACTERS, ContextMode.CHARACTER_TAIL), 512);

        List<ChunkDraft> drafts = new GeneralChunkPlanningStrategy(codePointCounter()).plan(request).drafts();

        assertEquals(List.of(64, 1), drafts.stream().map(draft -> UnicodeText.length(draft.content())).toList());
        assertEquals(source, drafts.stream().map(ChunkDraft::content).reduce("", String::concat));
        drafts.forEach(draft -> assertWellFormedUtf16(draft.content(), -1));
    }

    private String generatedDocument(Random random) {
        int segments = 2 + random.nextInt(5);
        StringBuilder result = new StringBuilder();
        for (int segment = 0; segment < segments; segment++) {
            if (segment > 0) result.append(DELIMITER);
            result.append(segment % 2 == 0 ? " \t" : "");
            result.append(generatedText(random, 20 + random.nextInt(75)));
            if (segment == 0 || segment % 3 == 0) result.append(' ').append(URL);
            if (segment == 1 || segment % 3 == 0) result.append('\t').append(EMAIL);
            result.append(segment % 2 == 0 ? "\r\n" : "\r");
            result.append('\u0000').append('\u0002');
        }
        return result.toString();
    }

    private String generatedText(Random random, int codePoints) {
        int[] alphabet = {'a', 'Z', '中', '。', '.', ' ', '\n', '\t', 0x1F600, 0x1D7D8};
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < codePoints; index++) {
            result.appendCodePoint(alphabet[random.nextInt(alphabet.length)]);
        }
        return result.toString();
    }

    private List<String> referenceCleanedSegments(String source, boolean collapseWhitespace,
                                                   boolean removeUrls, boolean removeEmails) {
        String normalized = referenceNormalize(source);
        String[] segments = normalized.split("\\Q" + DELIMITER + "\\E", -1);
        List<String> result = new ArrayList<>();
        for (String segment : segments) {
            String cleaned = segment;
            if (removeUrls) cleaned = cleaned.replace(URL, " ");
            if (removeEmails) cleaned = cleaned.replace(EMAIL, " ");
            if (collapseWhitespace) cleaned = referenceCollapseWhitespace(cleaned);
            if (!cleaned.isBlank()) result.add(cleaned);
        }
        return result;
    }

    private String referenceNormalize(String source) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < source.length();) {
            int codePoint = source.codePointAt(index);
            int width = Character.charCount(codePoint);
            if (codePoint == '\r') {
                if (index + 1 < source.length() && source.charAt(index + 1) == '\n') index++;
                result.append('\n');
            } else if (Character.getType(codePoint) != Character.CONTROL
                    || codePoint == '\n' || codePoint == '\t') {
                result.appendCodePoint(codePoint);
            }
            index += width;
        }
        return result.toString();
    }

    private String referenceCollapseWhitespace(String input) {
        StringBuilder result = new StringBuilder();
        int[] points = input.codePoints().toArray();
        for (int index = 0; index < points.length;) {
            if (!isWhitespace(points[index])) {
                result.appendCodePoint(points[index++]);
                continue;
            }
            int start = index;
            int lineFeeds = 0;
            while (index < points.length && isWhitespace(points[index])) {
                if (points[index] == '\n') lineFeeds++;
                index++;
            }
            if (lineFeeds > 0) result.append("\n".repeat(Math.min(2, lineFeeds)));
            else if (start > 0 && index < points.length) result.append(' ');
        }
        return result.toString();
    }

    private boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private String reconstruct(List<ChunkDraft> drafts) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < drafts.size(); index++) {
            ChunkDraft draft = drafts.get(index);
            result.append(draft.content());
            if (index + 1 < drafts.size()
                    && BoundaryKind.USER_DELIMITER.name().equals(draft.boundaryReason().get("end"))) {
                result.append('\n');
            }
        }
        return result.toString();
    }

    private TokenCounter codePointCounter() {
        return new TokenCounter() {
            @Override public int count(String text) { return UnicodeText.length(text); }
            @Override public String id() { return "property-code-point"; }
        };
    }

    private void assertWellFormedUtf16(String value, int sample) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                assertTrue(index + 1 < value.length() && Character.isLowSurrogate(value.charAt(++index)),
                        "sample " + sample);
            } else {
                assertFalse(Character.isLowSurrogate(current), "sample " + sample);
            }
        }
    }
}
