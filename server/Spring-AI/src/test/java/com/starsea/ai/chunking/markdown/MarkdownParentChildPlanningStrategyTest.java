package com.starsea.ai.chunking.markdown;

import com.starsea.ai.chunking.model.BlockType;
import com.starsea.ai.chunking.model.ChunkPlan;
import com.starsea.ai.chunking.model.ChunkType;
import com.starsea.ai.chunking.model.FileResource;
import com.starsea.ai.chunking.model.ParsedStructure;
import com.starsea.ai.chunking.model.PlannedChunk;
import com.starsea.ai.chunking.model.SourceLocator;
import com.starsea.ai.chunking.model.StructuredBlock;
import com.starsea.ai.chunking.spi.TokenCounter;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownParentChildPlanningStrategyTest {

    private final TokenCounter counter = new CharacterTokenCounter();
    private final MarkdownParentChildPlanningStrategy strategy = new MarkdownParentChildPlanningStrategy(counter);

    @Test
    void paragraph_mode_groups_children_under_bounded_parents_without_crossing_sections() {
        ChunkPlan plan = strategy.planConfigured(twoSections(), Map.of(
                "parentMode", "PARAGRAPH",
                "parentMaxTokens", 300,
                "childMaxTokens", 120,
                "childOverlapTokens", 24));

        assertTrue(plan.chunks().stream().anyMatch(chunk -> chunk.type() == ChunkType.PARENT));
        assertTrue(plan.chunks().stream().anyMatch(chunk -> chunk.type() == ChunkType.CHILD));
        assertEquals(120, plan.indexMaxTokens());
        assertEveryChildReferencesEarlierParent(plan);
        assertParentsDoNotMixSectionPaths(plan);
        assertTrue(plan.chunks().stream().filter(chunk -> chunk.type() == ChunkType.CHILD)
                .allMatch(PlannedChunk::overlapEnabled));
        assertTrue(plan.chunks().stream().filter(chunk -> chunk.type() == ChunkType.PARENT)
                .noneMatch(PlannedChunk::overlapEnabled));
    }

    @Test
    void full_document_mode_has_one_parent_and_all_children_reference_it() {
        ChunkPlan plan = strategy.planConfigured(twoSections(), Map.of(
                "parentMode", "FULL_DOCUMENT",
                "parentMaxTokens", 1024,
                "childMaxTokens", 128,
                "childOverlapTokens", 0));

        List<PlannedChunk> parents = plan.chunks().stream()
                .filter(chunk -> chunk.type() == ChunkType.PARENT).toList();
        assertEquals(1, parents.size());
        assertTrue(parents.get(0).draft().content().contains("# Alpha"));
        assertTrue(parents.get(0).draft().content().contains("# Beta"));
        assertTrue(plan.chunks().stream().filter(chunk -> chunk.type() == ChunkType.CHILD)
                .allMatch(chunk -> parents.get(0).key().equals(chunk.parentKey())));
        assertTrue(plan.chunks().stream().filter(chunk -> chunk.type() == ChunkType.CHILD)
                .noneMatch(PlannedChunk::overlapEnabled));
    }

    @Test
    void normalizes_only_integral_numbers_and_rejects_invalid_parent_child_combinations() {
        assertEquals(Map.of(
                "parentMode", "PARAGRAPH",
                "parentMaxTokens", 1024,
                "childMaxTokens", 256,
                "childOverlapTokens", 32), strategy.normalizeConfig(Map.of()));
        assertEquals(128, strategy.normalizeConfig(Map.of(
                "parentMaxTokens", 128L, "childMaxTokens", 128)).get("parentMaxTokens"));

        assertThrows(IllegalArgumentException.class, () -> strategy.normalizeConfig(Map.of("parentMode", "section")));
        assertThrows(IllegalArgumentException.class, () -> strategy.normalizeConfig(Map.of("parentMaxTokens", 127)));
        assertThrows(IllegalArgumentException.class, () -> strategy.normalizeConfig(Map.of("parentMaxTokens", 4097)));
        assertThrows(IllegalArgumentException.class, () -> strategy.normalizeConfig(Map.of("childMaxTokens", 31)));
        assertThrows(IllegalArgumentException.class, () -> strategy.normalizeConfig(Map.of("childMaxTokens", 513)));
        assertThrows(IllegalArgumentException.class, () -> strategy.normalizeConfig(Map.of("childOverlapTokens", -1)));
        assertThrows(IllegalArgumentException.class, () -> strategy.normalizeConfig(Map.of("childOverlapTokens", 129)));
        assertThrows(IllegalArgumentException.class, () -> strategy.normalizeConfig(Map.of(
                "childMaxTokens", 128, "childOverlapTokens", 128)));
        assertThrows(IllegalArgumentException.class, () -> strategy.normalizeConfig(Map.of(
                "parentMode", "PARAGRAPH", "parentMaxTokens", 255, "childMaxTokens", 256)));
        assertThrows(IllegalArgumentException.class, () -> strategy.normalizeConfig(Map.of("childMaxTokens", 128.5)));
        assertThrows(IllegalArgumentException.class, () -> strategy.normalizeConfig(Map.of("childMaxTokens", "128")));
    }

    @Test
    void assigns_stable_sibling_positions_and_exposes_descriptor_defaults() {
        ChunkPlan plan = strategy.planConfigured(twoSections(), Map.of(
                "parentMaxTokens", 300,
                "childMaxTokens", 120));

        List<PlannedChunk> parents = plan.chunks().stream()
                .filter(chunk -> chunk.type() == ChunkType.PARENT).toList();
        assertEquals(List.of(0, 1), parents.stream().map(PlannedChunk::siblingPosition).toList());
        for (PlannedChunk parent : parents) {
            assertEquals(List.of(0, 1), plan.chunks().stream()
                    .filter(chunk -> parent.key().equals(chunk.parentKey()))
                    .map(PlannedChunk::siblingPosition).toList());
        }
        assertEquals(List.of("parentMode", "parentMaxTokens", "childMaxTokens", "childOverlapTokens"),
                strategy.descriptor().configFields().stream().map(field -> field.key()).toList());
        assertEquals("PARAGRAPH", strategy.descriptor().configFields().get(0).defaultValue());
        assertEquals(1024, strategy.descriptor().configFields().get(1).defaultValue());
        assertEquals(256, strategy.descriptor().configFields().get(2).defaultValue());
        assertEquals(32, strategy.descriptor().configFields().get(3).defaultValue());
    }

    @Test
    void paragraph_parent_capacity_uses_the_same_child_order_as_the_rendered_parent() {
        MarkdownParentChildPlanningStrategy orderSensitiveStrategy =
                new MarkdownParentChildPlanningStrategy(new OrderSensitiveTokenCounter());
        ParsedStructure structure = structure(List.of(
                block("first", BlockType.PARAGRAPH, "first child", "first child", null, List.of("Section")),
                block("second", BlockType.PARAGRAPH, "second child", "second child", null, List.of("Section"))));

        ChunkPlan plan = orderSensitiveStrategy.planConfigured(structure, Map.of(
                "parentMaxTokens", 128,
                "childMaxTokens", 32,
                "childOverlapTokens", 0));

        List<PlannedChunk> parents = plan.chunks().stream()
                .filter(chunk -> chunk.type() == ChunkType.PARENT).toList();
        assertEquals(1, parents.size());
        assertEquals("first child\n\nsecond child", parents.get(0).draft().content());
        assertEquals(2, plan.chunks().stream().filter(chunk -> chunk.type() == ChunkType.CHILD).count());
    }

    private void assertEveryChildReferencesEarlierParent(ChunkPlan plan) {
        for (int index = 0; index < plan.chunks().size(); index++) {
            PlannedChunk chunk = plan.chunks().get(index);
            if (chunk.type() != ChunkType.CHILD) {
                continue;
            }
            assertTrue(plan.chunks().subList(0, index).stream()
                    .anyMatch(parent -> parent.type() == ChunkType.PARENT && parent.key().equals(chunk.parentKey())));
        }
    }

    private void assertParentsDoNotMixSectionPaths(ChunkPlan plan) {
        for (PlannedChunk parent : plan.chunks().stream().filter(chunk -> chunk.type() == ChunkType.PARENT).toList()) {
            List<List<String>> childPaths = plan.chunks().stream()
                    .filter(chunk -> parent.key().equals(chunk.parentKey()))
                    .map(chunk -> chunk.draft().sectionPath()).distinct().toList();
            assertEquals(1, childPaths.size());
            assertEquals(childPaths.get(0), parent.draft().sectionPath());
        }
    }

    private ParsedStructure twoSections() {
        return new ParsedStructure(new FileResource(1, 2, 3,
                UUID.fromString("00000000-0000-0000-0000-000000000003"), "test.md", "md", Path.of("test.md")), List.of(
                block("h1", BlockType.HEADING, "# Alpha", "Alpha", 1, List.of("Alpha")),
                block("a1", BlockType.PARAGRAPH, "a".repeat(80), "a".repeat(80), null, List.of("Alpha")),
                block("a2", BlockType.PARAGRAPH, "b".repeat(80), "b".repeat(80), null, List.of("Alpha")),
                block("h2", BlockType.HEADING, "# Beta", "Beta", 1, List.of("Beta")),
                block("b1", BlockType.PARAGRAPH, "c".repeat(80), "c".repeat(80), null, List.of("Beta")),
                block("b2", BlockType.PARAGRAPH, "d".repeat(80), "d".repeat(80), null, List.of("Beta"))));
    }

    private ParsedStructure structure(List<StructuredBlock> blocks) {
        return new ParsedStructure(new FileResource(1, 2, 3,
                UUID.fromString("00000000-0000-0000-0000-000000000003"), "test.md", "md", Path.of("test.md")), blocks);
    }

    private StructuredBlock block(String id, BlockType type, String rawText, String plainText,
                                  Integer headingLevel, List<String> path) {
        return new StructuredBlock(id, type, rawText, plainText, headingLevel, path,
                counter.count(plainText), new SourceLocator("markdown", List.of(id), null, null,
                null, null, null, null, List.of()), Map.of());
    }

    private static final class CharacterTokenCounter implements TokenCounter {
        @Override
        public int count(String text) {
            return text == null ? 0 : text.codePointCount(0, text.length());
        }

        @Override
        public String id() {
            return "test-code-point-counter";
        }
    }

    private static final class OrderSensitiveTokenCounter implements TokenCounter {
        @Override
        public int count(String text) {
            if (text != null && text.contains("first child\n\nsecond child")) {
                return 128;
            }
            if (text != null && text.contains("second child\n\nfirst child")) {
                return 129;
            }
            return text == null ? 0 : text.codePointCount(0, text.length());
        }

        @Override
        public String id() {
            return "test-order-sensitive-counter";
        }
    }
}
