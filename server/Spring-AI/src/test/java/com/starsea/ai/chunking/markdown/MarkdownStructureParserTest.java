package com.starsea.ai.chunking.markdown;

import com.starsea.ai.chunking.model.BlockType;
import com.starsea.ai.chunking.model.FileResource;
import com.starsea.ai.chunking.model.ParsedStructure;
import com.starsea.ai.chunking.model.StructuredBlock;
import com.starsea.ai.chunking.spi.TokenCounter;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownStructureParserTest {

    private final MarkdownStructureParser parser = new MarkdownStructureParser(new CharacterTokenCounter());

    @Test
    void preserves_heading_paths_and_source_backed_rich_blocks() throws URISyntaxException {
        ParsedStructure parsed = parser.parse(sampleResource());

        StructuredBlock passwordBlock = blockWithPlainText(parsed, "重置密码");
        StructuredBlock tableBlock = blockOfType(parsed, BlockType.TABLE);
        StructuredBlock listBlock = blockOfType(parsed, BlockType.UNORDERED_LIST);
        StructuredBlock codeBlock = blockOfType(parsed, BlockType.FENCED_CODE);
        StructuredBlock quoteBlock = blockOfType(parsed, BlockType.BLOCK_QUOTE);
        StructuredBlock htmlBlock = blockOfType(parsed, BlockType.HTML_BLOCK);
        StructuredBlock thematicBreak = blockOfType(parsed, BlockType.THEMATIC_BREAK);
        StructuredBlock accountParagraph = blockWithPlainText(parsed, "账户说明。");

        assertEquals(List.of("指南", "网络", "重置密码"), passwordBlock.sectionPath());
        assertEquals(BlockType.TABLE, tableBlock.type());
        assertEquals(7, tableBlock.sourceLocator().startLine());
        assertEquals("| 操作 | 说明 |\n| --- | --- |\n| 重置 | 点击“忘记密码” |", tableBlock.rawText());
        assertEquals("- 第一步\n  - 嵌套步骤", listBlock.rawText());
        assertEquals("```text\n  保留 `围栏` 与标记\n```", codeBlock.rawText());
        assertEquals("  保留 `围栏` 与标记", codeBlock.plainText());
        assertEquals("> 引用内容\n>\n> 第二行", quoteBlock.rawText());
        assertEquals("<div class=\"notice\">\n<strong>HTML 内容</strong>\n</div>", htmlBlock.rawText());
        assertEquals("---", thematicBreak.rawText());
        assertEquals(List.of("指南", "账户"), accountParagraph.sectionPath());
        assertEquals(16, accountParagraph.sourceLocator().startLine());
        assertTrue(parsed.blocks().stream().anyMatch(block ->
                block.type() == BlockType.HEADING && block.plainText().equals("空章节")));
    }

    private FileResource sampleResource() throws URISyntaxException {
        Path path = Path.of(getClass().getResource("/chunking/markdown/structure-sample.md").toURI());
        return new FileResource(1L, 2L, 3L, UUID.fromString("00000000-0000-0000-0000-000000000003"),
                "structure-sample.md", "md", path);
    }

    private StructuredBlock blockOfType(ParsedStructure parsed, BlockType type) {
        return parsed.blocks().stream().filter(block -> block.type() == type).findFirst().orElseThrow();
    }

    private StructuredBlock blockWithPlainText(ParsedStructure parsed, String plainText) {
        return parsed.blocks().stream().filter(block -> block.plainText().equals(plainText)).findFirst().orElseThrow();
    }

    private static final class CharacterTokenCounter implements TokenCounter {

        @Override
        public int count(String text) {
            return text == null ? 0 : text.length();
        }

        @Override
        public String id() {
            return "test-character-counter";
        }
    }
}
