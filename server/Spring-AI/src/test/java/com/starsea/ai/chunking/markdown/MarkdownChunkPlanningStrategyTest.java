package com.starsea.ai.chunking.markdown;

import com.starsea.ai.chunking.model.BlockType;
import com.starsea.ai.chunking.model.ChunkDraft;
import com.starsea.ai.chunking.model.ChunkPolicy;
import com.starsea.ai.chunking.model.FileResource;
import com.starsea.ai.chunking.model.ParsedStructure;
import com.starsea.ai.chunking.model.SemanticUnit;
import com.starsea.ai.chunking.model.SourceLocator;
import com.starsea.ai.chunking.model.StructuredBlock;
import com.starsea.ai.chunking.spi.TokenCounter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownChunkPlanningStrategyTest {

    @TempDir
    Path tempDir;

    private final TokenCounter counter = new CharacterTokenCounter();
    private final MarkdownChunkPlanningStrategy strategy = new MarkdownChunkPlanningStrategy(counter);

    @Test
    void exposes_the_registered_markdown_strategy_contract() {
        assertEquals("MARKDOWN_OPTIMIZED", strategy.code());
        assertEquals("markdown-adaptive-v1", strategy.plannerVersion());
        assertEquals("FILE_TYPE", strategy.descriptor().scope());
        assertEquals(List.of("maxTokens", "minTokens", "targetTokens"), strategy.descriptor().configFields()
                .stream().map(field -> field.key()).sorted().toList());
        assertTrue(strategy.descriptor().configFields().stream().allMatch(field -> "number".equals(field.type())));
    }

    @Test
    void title_only_sections_produce_no_chunks() {
        ParsedStructure structure = structure(List.of(
                block("h1", BlockType.HEADING, "# 文档", "文档", 1, List.of("文档")),
                block("h2", BlockType.HEADING, "## 空章节", "空章节", 2, List.of("文档", "空章节"))));

        assertTrue(strategy.plan(structure, new ChunkPolicy(5, 20, 40)).isEmpty());
    }

    @Test
    void persisted_token_count_measures_body_only_while_fit_includes_the_title_path() {
        ParsedStructure structure = structure(List.of(
                block("p1", BlockType.PARAGRAPH, "正文", "正文", null,
                        List.of("很长的标题"))));

        ChunkDraft chunk = strategy.plan(structure, new ChunkPolicy(1, 30, 40)).get(0);

        assertEquals(2, chunk.tokenCount());
        assertEquals(12, counter.count(indexText(chunk)));
    }

    @Test
    void guide_paragraph_stays_with_following_list_table_and_code_when_each_pair_fits() {
        List<ContainerCase> cases = List.of(
                new ContainerCase(BlockType.UNORDERED_LIST, "- 第一步\n- 第二步"),
                new ContainerCase(BlockType.TABLE, "| 项目 | 值 |\n| --- | --- |\n| A | 1 |"),
                new ContainerCase(BlockType.FENCED_CODE, "```text\nrun\n```"));

        for (ContainerCase containerCase : cases) {
            List<String> path = List.of("指南");
            ParsedStructure structure = structure(List.of(
                    block("guide", BlockType.PARAGRAPH, "步骤如下：", "步骤如下：", null, path),
                    block("container", containerCase.type(), containerCase.rawText(),
                            containerCase.rawText(), null, path)));

            List<ChunkDraft> chunks = strategy.plan(structure, new ChunkPolicy(1, 8, 80));

            assertEquals(1, chunks.size(), containerCase.type().name());
            assertTrue(chunks.get(0).content().contains("步骤如下："), containerCase.type().name());
            assertTrue(chunks.get(0).content().contains(containerCase.rawText()), containerCase.type().name());
        }
    }

    @Test
    void oversized_guided_container_reserves_room_for_the_guide_in_its_first_subunit() {
        List<String> path = List.of("指南");
        String list = "- 项目一内容\n- 项目二内容\n- 项目三内容\n- 项目四内容";
        ParsedStructure structure = structure(List.of(
                block("guide", BlockType.PARAGRAPH, "具体步骤如下：", "具体步骤如下：", null, path),
                block("list", BlockType.UNORDERED_LIST, list, list, null, path)));

        List<ChunkDraft> chunks = strategy.plan(structure, new ChunkPolicy(5, 30, 45));

        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.get(0).content().contains("具体步骤如下："));
        assertTrue(chunks.get(0).content().contains("- 项目一内容"));
        assertTrue(chunks.stream().allMatch(chunk -> counter.count(indexText(chunk)) <= 45));
    }

    @Test
    void peer_labels_begin_independent_candidate_units_without_qa_parsing() {
        List<String> labels = List.of("Q1：问题", "1. 条目", "（一）范围", "第一条 规定", "步骤 1 操作");
        List<StructuredBlock> blocks = new ArrayList<>();
        for (int index = 0; index < labels.size(); index++) {
            String label = labels.get(index);
            blocks.add(block("p" + index, BlockType.PARAGRAPH, label, label, null, List.of("同级条目")));
        }

        List<SemanticUnit> units = new MarkdownSemanticUnitBuilder(counter).build(structure(blocks));

        assertEquals(labels.size(), units.size());
        assertEquals(labels, units.stream().map(unit -> unit.blocks().get(0).plainText()).toList());
        assertTrue(units.stream().allMatch(unit -> "PEER_LABEL".equals(unit.attributes().get("start"))));
    }

    @Test
    void oversized_window_prefers_a_high_scored_boundary_after_minimum_over_the_farthest_fit() {
        List<String> path = List.of();
        ParsedStructure structure = structure(List.of(
                block("p1", BlockType.PARAGRAPH, "abcdefghij", "abcdefghij", null, path),
                block("p2", BlockType.PARAGRAPH, "Q1：abcdef", "Q1：abcdef", null, path),
                block("p3", BlockType.PARAGRAPH, "klmnopqrst", "klmnopqrst", null, path),
                block("p4", BlockType.PARAGRAPH, "uvwxyzABCD", "uvwxyzABCD", null, path)));

        List<ChunkDraft> chunks = strategy.plan(structure, new ChunkPolicy(5, 35, 38));

        assertEquals("abcdefghij", chunks.get(0).content());
        assertEquals("PEER_LABEL", chunks.get(0).boundaryReason().get("end"));
    }

    @Test
    void oversized_prose_prefers_complete_sentence_boundaries() {
        String prose = "第一句内容完整结束。第二句内容同样完整结束。第三句内容也完整结束。";
        ParsedStructure structure = structure(List.of(
                block("p1", BlockType.PARAGRAPH, prose, prose, null, List.of("说明"))));

        List<ChunkDraft> chunks = strategy.plan(structure, new ChunkPolicy(8, 18, 28));

        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.content().endsWith("。")));
        assertTrue(chunks.stream().allMatch(chunk -> counter.count(indexText(chunk)) <= 28));
    }

    @Test
    void oversized_unterminated_four_marker_code_fences_keep_matching_synthesized_closers() {
        List<FenceCase> cases = List.of(
                new FenceCase("````java", "````"),
                new FenceCase("~~~~java", "~~~~"));

        for (FenceCase fenceCase : cases) {
            String code = fenceCase.opening() + "\n" + "long-code-line-".repeat(6);
            ParsedStructure structure = structure(List.of(
                    block("code", BlockType.FENCED_CODE, code, code, null, List.of())));

            List<ChunkDraft> chunks = strategy.plan(structure, new ChunkPolicy(5, 20, 30));

            assertTrue(chunks.size() >= 2, fenceCase.opening());
            assertTrue(chunks.stream().allMatch(chunk -> chunk.content().startsWith(fenceCase.opening() + "\n")),
                    fenceCase.opening());
            assertTrue(chunks.stream().allMatch(chunk -> chunk.content().endsWith("\n" + fenceCase.closing())),
                    fenceCase.opening());
            assertTrue(chunks.stream().allMatch(chunk -> counter.count(indexText(chunk)) <= 30),
                    fenceCase.opening());
        }
    }

    @Test
    void opposite_marker_final_line_remains_code_body_and_does_not_close_the_fence() {
        String code = "````\n" + "long-code-line-".repeat(5) + "\n~~~~";
        ParsedStructure structure = structure(List.of(
                block("code", BlockType.FENCED_CODE, code, code, null, List.of())));

        List<ChunkDraft> chunks = strategy.plan(structure, new ChunkPolicy(5, 20, 30));

        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.content().endsWith("\n````")));
        assertEquals(1, chunks.stream().mapToInt(chunk -> occurrences(chunk.content(), "~~~~")).sum());
        assertTrue(chunks.stream().allMatch(chunk -> counter.count(indexText(chunk)) <= 30));
    }

    @Test
    void oversized_table_repeats_header_in_every_chunk() {
        String table = "| 项目 | 说明 |\n"
                + "| --- | --- |\n"
                + "| A | 第一项内容 |\n"
                + "| B | 第二项内容 |\n"
                + "| C | 第三项内容 |\n"
                + "| D | 第四项内容 |";
        ParsedStructure structure = structure(List.of(
                block("table", BlockType.TABLE, table, "项目 说明 A 第一项内容 B 第二项内容 C 第三项内容 D 第四项内容",
                        null, List.of("数据"))));

        List<ChunkDraft> chunks = strategy.plan(structure, new ChunkPolicy(15, 35, 52));

        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.content().startsWith("| 项目 | 说明 |\n| --- | --- |")));
        assertTrue(chunks.stream().allMatch(chunk -> counter.count(indexText(chunk)) <= 52));
    }

    @Test
    void a_single_oversized_table_row_uses_token_safe_fragments_without_duplicating_its_header() {
        String header = "| 项目 | 说明 |\n| --- | --- |";
        String table = header + "\n| A | " + "很长内容".repeat(16) + " |";
        ParsedStructure structure = structure(List.of(
                block("table", BlockType.TABLE, table, table, null, List.of("数据"))));

        List<ChunkDraft> chunks = strategy.plan(structure, new ChunkPolicy(10, 35, 52));

        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.content().startsWith(header)));
        assertTrue(chunks.stream().allMatch(chunk -> occurrences(chunk.content(), header) == 1));
        assertTrue(chunks.stream().allMatch(chunk -> counter.count(indexText(chunk)) <= 52));
    }

    @Test
    void short_tails_merge_only_when_section_paths_are_identical() {
        String body = "主体内容足够形成一个分块。";
        String tail = "短尾。";
        ChunkPolicy policy = new ChunkPolicy(18, 22, 48);

        List<ChunkDraft> samePath = strategy.plan(structure(List.of(
                block("p1", BlockType.PARAGRAPH, body, body, null, List.of("章节甲")),
                block("p2", BlockType.PARAGRAPH, tail, tail, null, List.of("章节甲")))), policy);
        List<ChunkDraft> differentPaths = strategy.plan(structure(List.of(
                block("p1", BlockType.PARAGRAPH, body, body, null, List.of("章节甲")),
                block("p2", BlockType.PARAGRAPH, tail, tail, null, List.of("章节乙")))), policy);

        assertEquals(1, samePath.size());
        assertTrue(samePath.get(0).content().contains(tail));
        assertEquals(2, differentPaths.size());
        assertFalse(differentPaths.get(0).content().contains(tail));
        assertEquals(List.of("章节乙"), differentPaths.get(1).sectionPath());
    }

    @Test
    void short_tails_do_not_merge_across_thematic_or_peer_label_boundaries() {
        String body = "主体内容足够形成一个分块。";
        ChunkPolicy policy = new ChunkPolicy(18, 22, 48);
        List<String> path = List.of("章节甲");

        List<ChunkDraft> thematic = strategy.plan(structure(List.of(
                block("p1", BlockType.PARAGRAPH, body, body, null, path),
                block("break", BlockType.THEMATIC_BREAK, "---", "", null, path),
                block("p2", BlockType.PARAGRAPH, "短尾。", "短尾。", null, path))), policy);
        List<ChunkDraft> peer = strategy.plan(structure(List.of(
                block("p1", BlockType.PARAGRAPH, body, body, null, path),
                block("p2", BlockType.PARAGRAPH, "Q1：短尾。", "Q1：短尾。", null, path))), policy);

        assertEquals(2, thematic.size());
        assertEquals(2, peer.size());
    }

    @Test
    void chunk_locators_keep_every_atom_region_once() {
        Map<String, Object> firstRegion = Map.of("id", "first");
        Map<String, Object> middleRegion = Map.of("id", "middle");
        Map<String, Object> lastRegion = Map.of("id", "last");
        ChunkPolicy policy = new ChunkPolicy(1, 50, 80);

        List<ChunkDraft> threeAtoms = strategy.plan(structure(List.of(
                blockWithRegion("p1", "第一段", firstRegion),
                blockWithRegion("p2", "第二段", middleRegion),
                blockWithRegion("p3", "第三段", lastRegion))), policy);
        List<ChunkDraft> oneAtom = strategy.plan(structure(List.of(
                blockWithRegion("only", "单独段落", firstRegion))), policy);

        assertEquals(1, threeAtoms.size());
        assertEquals(List.of(firstRegion, middleRegion, lastRegion), threeAtoms.get(0).sourceLocator().regions());
        assertEquals(1, oneAtom.size());
        assertEquals(List.of(firstRegion), oneAtom.get(0).sourceLocator().regions());
    }

    @Test
    void token_safe_split_chooses_furthest_fitting_unicode_prefix_with_non_monotonic_counts() {
        TokenCounter nonMonotonicCounter = new NonMonotonicTokenCounter();
        MarkdownChunkPlanningStrategy nonMonotonicStrategy =
                new MarkdownChunkPlanningStrategy(nonMonotonicCounter);
        String content = "😀bcde";
        ParsedStructure structure = structure(List.of(
                block("p1", BlockType.PARAGRAPH, content, content, null, List.of())));

        List<ChunkDraft> chunks = nonMonotonicStrategy.plan(structure, new ChunkPolicy(1, 5, 10));

        assertEquals(List.of("😀bcd", "e"), chunks.stream().map(ChunkDraft::content).toList());
        assertEquals(content, chunks.stream().map(ChunkDraft::content).reduce("", String::concat));
        assertTrue(chunks.stream().allMatch(chunk -> nonMonotonicCounter.count(indexText(chunk)) <= 10));
    }

    @Test
    void oversized_paragraph_parts_have_exact_non_repeated_source_ranges() throws IOException {
        String source = "第一句内容完整结束。第二句内容同样完整结束。第三句内容也完整结束。";

        List<ChunkDraft> chunks = strategy.plan(parse(source), new ChunkPolicy(5, 16, 24));

        assertTrue(chunks.size() >= 2);
        for (ChunkDraft chunk : chunks) {
            SourceLocator locator = chunk.sourceLocator();
            assertEquals(chunk.content(), source.substring(locator.startOffset(), locator.endOffset()));
        }
        assertStrictlyIncreasingRanges(chunks);
    }

    @Test
    void oversized_fenced_code_parts_own_disjoint_body_lines_not_synthetic_fences() throws IOException {
        String source = "```text\nalpha-alpha-alpha\nbeta-beta-beta\ngamma-gamma-gamma\n```";

        List<ChunkDraft> chunks = strategy.plan(parse(source), new ChunkPolicy(5, 20, 30));

        assertTrue(chunks.size() >= 2);
        assertEquals(0, chunks.get(0).sourceLocator().startOffset());
        assertEquals(source.length(), chunks.get(chunks.size() - 1).sourceLocator().endOffset());
        for (int index = 1; index < chunks.size(); index++) {
            SourceLocator locator = chunks.get(index).sourceLocator();
            assertFalse(source.substring(locator.startOffset(), locator.endOffset()).contains("```text"));
        }
        assertStrictlyIncreasingRanges(chunks);
    }

    @Test
    void repeated_table_headers_are_context_only_and_later_parts_start_on_owned_rows() throws IOException {
        String header = "| 项目 | 说明 |\n| --- | --- |";
        String source = header + "\n| A | 第一项内容很长 |\n| B | 第二项内容很长 |\n| C | 第三项内容很长 |";

        List<ChunkDraft> chunks = strategy.plan(parse(source), new ChunkPolicy(8, 30, 45));

        assertTrue(chunks.size() >= 2);
        assertTrue(source.substring(chunks.get(0).sourceLocator().startOffset(),
                chunks.get(0).sourceLocator().endOffset()).contains(header));
        for (int index = 1; index < chunks.size(); index++) {
            SourceLocator locator = chunks.get(index).sourceLocator();
            String ownedSource = source.substring(locator.startOffset(), locator.endOffset());
            assertFalse(ownedSource.contains("| 项目 | 说明 |"));
            assertTrue(locator.startLine() >= 3);
        }
        assertStrictlyIncreasingRanges(chunks);
    }

    @Test
    void recursive_locator_projection_preserves_original_crlf_offsets() throws IOException {
        String source = "| A | B |\r\n| --- | --- |\r\n| 1 | row-one-long |\r\n"
                + "| 2 | row-two-long |\r\n| 3 | row-three-long |";

        List<ChunkDraft> chunks = strategy.plan(parse(source), new ChunkPolicy(8, 25, 38));

        assertTrue(chunks.size() >= 2);
        SourceLocator second = chunks.get(1).sourceLocator();
        assertFalse(source.substring(second.startOffset(), second.endOffset()).contains("| A | B |"));
        assertTrue(second.startLine() >= 3);
        assertStrictlyIncreasingRanges(chunks);
    }

    private ParsedStructure parse(String source) throws IOException {
        Path path = tempDir.resolve(UUID.randomUUID() + ".md");
        Files.writeString(path, source);
        FileResource resource = new FileResource(1, 2, 3, UUID.randomUUID(),
                path.getFileName().toString(), "md", path);
        return new MarkdownStructureParser(counter).parse(resource);
    }

    private void assertStrictlyIncreasingRanges(List<ChunkDraft> chunks) {
        int previousEnd = -1;
        for (ChunkDraft chunk : chunks) {
            SourceLocator locator = chunk.sourceLocator();
            assertTrue(locator.startOffset() >= previousEnd);
            assertTrue(locator.endOffset() > locator.startOffset());
            previousEnd = locator.endOffset();
        }
    }

    private ParsedStructure structure(List<StructuredBlock> blocks) {
        FileResource resource = new FileResource(1, 2, 3,
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                "test.md", "md", Path.of("test.md"));
        return new ParsedStructure(resource, blocks);
    }

    private StructuredBlock block(String id, BlockType type, String rawText, String plainText,
                                  Integer headingLevel, List<String> path) {
        return new StructuredBlock(id, type, rawText, plainText, headingLevel, path,
                counter.count(plainText), locator(id), Map.of());
    }

    private SourceLocator locator(String id) {
        return new SourceLocator("markdown", List.of(id), null, null, null, null, null, null, List.of());
    }

    private StructuredBlock blockWithRegion(String id, String text, Map<String, Object> region) {
        SourceLocator locator = new SourceLocator(
                "markdown", List.of(id), null, null, null, null, null, null, List.of(region));
        return new StructuredBlock(id, BlockType.PARAGRAPH, text, text, null, List.of(),
                counter.count(text), locator, Map.of());
    }

    private String indexText(ChunkDraft chunk) {
        return chunk.sectionPath().isEmpty()
                ? chunk.content()
                : "标题：" + String.join(" > ", chunk.sectionPath()) + "\n\n" + chunk.content();
    }

    private int occurrences(String text, String needle) {
        return (text.length() - text.replace(needle, "").length()) / needle.length();
    }

    private record ContainerCase(BlockType type, String rawText) {
    }

    private record FenceCase(String opening, String closing) {
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

    private static final class NonMonotonicTokenCounter implements TokenCounter {

        @Override
        public int count(String text) {
            int codePoints = text == null ? 0 : text.codePointCount(0, text.length());
            return codePoints == 2 || codePoints == 3 || codePoints == 5 ? 20 : codePoints;
        }

        @Override
        public String id() {
            return "test-non-monotonic-counter";
        }
    }
}
