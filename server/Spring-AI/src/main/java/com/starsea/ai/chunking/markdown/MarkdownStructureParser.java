package com.starsea.ai.chunking.markdown;

import com.starsea.ai.chunking.model.BlockType;
import com.starsea.ai.chunking.model.FileResource;
import com.starsea.ai.chunking.model.ParsedStructure;
import com.starsea.ai.chunking.model.StructuredBlock;
import com.starsea.ai.chunking.spi.DocumentStructureParser;
import com.starsea.ai.chunking.spi.TokenCounter;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Parses Markdown into ordered, source-backed structural blocks. */
@Component
public final class MarkdownStructureParser implements DocumentStructureParser {

    private static final List<Extension> EXTENSIONS = List.of(TablesExtension.create());
    private static final Set<String> SUPPORTED_FILE_TYPES = Set.of("md", "markdown");

    private final TokenCounter tokenCounter;
    private final Parser parser;

    public MarkdownStructureParser(TokenCounter tokenCounter) {
        this.tokenCounter = Objects.requireNonNull(tokenCounter, "tokenCounter");
        this.parser = Parser.builder()
                .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
                .extensions(EXTENSIONS)
                .build();
    }

    @Override
    public Set<String> supportedFileTypes() {
        return SUPPORTED_FILE_TYPES;
    }

    @Override
    public ParsedStructure parse(FileResource resource) {
        Objects.requireNonNull(resource, "resource");
        String source = readSource(resource);
        MarkdownBlockRenderer renderer = new MarkdownBlockRenderer(source, EXTENSIONS);
        List<StructuredBlock> blocks = new ArrayList<>();
        List<HeadingEntry> headingStack = new ArrayList<>();

        Node document = parser.parse(source);
        int blockSequence = 1;
        for (Node node = document.getFirstChild(); node != null; node = node.getNext()) {
            Optional<BlockType> blockType = blockTypeOf(node);
            if (blockType.isEmpty()) {
                continue;
            }

            String plainText = renderer.plainText(node);
            Integer headingLevel = null;
            if (node instanceof Heading heading) {
                headingLevel = heading.getLevel();
                removeCurrentAndDeeperHeadings(headingStack, headingLevel);
                headingStack.add(new HeadingEntry(headingLevel, plainText));
            }

            String blockId = "markdown-" + blockSequence++;
            blocks.add(new StructuredBlock(
                    blockId,
                    blockType.get(),
                    renderer.rawText(node),
                    plainText,
                    headingLevel,
                    headingStack.stream().map(HeadingEntry::text).toList(),
                    tokenCounter.count(plainText),
                    renderer.sourceLocator(blockId, node),
                    Map.of()));
        }
        return new ParsedStructure(resource, blocks);
    }

    private String readSource(FileResource resource) {
        try {
            return Files.readString(resource.path(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read Markdown source: " + resource.path(), exception);
        }
    }

    private void removeCurrentAndDeeperHeadings(List<HeadingEntry> headingStack, int currentLevel) {
        while (!headingStack.isEmpty() && headingStack.get(headingStack.size() - 1).level() >= currentLevel) {
            headingStack.remove(headingStack.size() - 1);
        }
    }

    private Optional<BlockType> blockTypeOf(Node node) {
        if (node instanceof Heading) {
            return Optional.of(BlockType.HEADING);
        }
        if (node instanceof Paragraph) {
            return Optional.of(BlockType.PARAGRAPH);
        }
        if (node instanceof OrderedList) {
            return Optional.of(BlockType.ORDERED_LIST);
        }
        if (node instanceof BulletList) {
            return Optional.of(BlockType.UNORDERED_LIST);
        }
        if (node instanceof BlockQuote) {
            return Optional.of(BlockType.BLOCK_QUOTE);
        }
        if (node instanceof FencedCodeBlock) {
            return Optional.of(BlockType.FENCED_CODE);
        }
        if (node instanceof IndentedCodeBlock) {
            return Optional.of(BlockType.INDENTED_CODE);
        }
        if (node instanceof TableBlock) {
            return Optional.of(BlockType.TABLE);
        }
        if (node instanceof ThematicBreak) {
            return Optional.of(BlockType.THEMATIC_BREAK);
        }
        if (node instanceof HtmlBlock) {
            return Optional.of(BlockType.HTML_BLOCK);
        }
        return Optional.empty();
    }

    private record HeadingEntry(int level, String text) {
    }
}
