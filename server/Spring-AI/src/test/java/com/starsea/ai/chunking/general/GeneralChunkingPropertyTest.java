package com.starsea.ai.chunking.general;

import com.starsea.ai.chunking.context.ChunkIndexContentBuilder;
import com.starsea.ai.chunking.model.*;
import com.starsea.ai.chunking.spi.TokenCounter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneralChunkingPropertyTest {

    @Test
    void generated_unicode_inputs_are_lossless_bounded_surrogate_safe_and_deterministic() {
        Random random = new Random(0x5EA5EA);
        TokenCounter counter = new TokenCounter() {
            @Override public int count(String text) { return UnicodeText.length(text); }
            @Override public String id() { return "property-code-point"; }
        };
        GeneralChunkPlanningStrategy planner = new GeneralChunkPlanningStrategy(counter);

        for (int sample = 0; sample < 160; sample++) {
            String source = generatedText(random, 65 + random.nextInt(260));
            int maxCharacters = 64 + random.nextInt(65);
            int maxTokens = 32 + random.nextInt(481);
            GeneralChunkConfig config = new GeneralChunkConfig("|||", DelimiterMode.LITERAL,
                    maxCharacters, false, false, false);
            NormalizedText normalized = new TextNormalizer().normalize(source);
            CleaningResult cleaned = new GeneralTextCleaner().clean(
                    new GeneralBoundaryScanner(config).scan(normalized), config, normalized);
            String retained = cleaned.segments().get(0).text();
            StructuredBlock block = new StructuredBlock("property", BlockType.PARAGRAPH,
                    retained, retained, null, List.of(), counter.count(retained),
                    new SourceLocator("TEXT", List.of("property"), 0, retained.length(),
                            null, null, null, null, List.of()),
                    Map.of("boundaryAfter", "DOCUMENT_END"));
            ChunkPlanningRequest request = new ChunkPlanningRequest(new ParsedStructure(null, List.of(block)),
                    config, new ContextConfig(false, 0, OverlapUnit.CHARACTERS, ContextMode.CHARACTER_TAIL),
                    maxTokens);

            ChunkPlanningResult first = planner.plan(request);
            ChunkPlanningResult second = planner.plan(request);

            assertEquals(retained, first.drafts().stream().map(ChunkDraft::content)
                    .reduce("", String::concat), "sample " + sample);
            assertEquals(first, second, "sample " + sample);
            assertTrue(first.drafts().stream().allMatch(draft -> UnicodeText.length(draft.content()) <= maxCharacters));
            assertTrue(first.drafts().stream().allMatch(draft ->
                    counter.count(ChunkIndexContentBuilder.preview(draft.sectionPath(), draft.content())) <= maxTokens));
            assertTrue(first.drafts().stream().allMatch(draft -> draft.content().equals(
                    new String(draft.content().codePoints().toArray(), 0,
                            draft.content().codePointCount(0, draft.content().length())))));
        }
    }

    private String generatedText(Random random, int codePoints) {
        int[] alphabet = {'a', 'Z', '中', '。', '.', ' ', '\n', '\t', 0x1F600, 0x1D7D8};
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < codePoints; index++) {
            result.appendCodePoint(alphabet[random.nextInt(alphabet.length)]);
        }
        return result.toString();
    }
}
