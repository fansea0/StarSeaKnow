package com.starsea.ai.chunking.markdown;

import com.starsea.ai.chunking.model.BlockType;
import com.starsea.ai.chunking.model.ParsedStructure;
import com.starsea.ai.chunking.model.SemanticUnit;
import com.starsea.ai.chunking.model.StructuredBlock;
import com.starsea.ai.chunking.spi.TokenCounter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Groups adjacent Markdown blocks whose relationship is stronger than a normal chunk boundary. */
public final class MarkdownSemanticUnitBuilder {

    private static final Pattern PEER_LABEL_PATTERN = Pattern.compile(
            "^(?:Q\\d+\\s*[：:]|\\d+[.、．]\\s*|[（(][一二三四五六七八九十百千]+[）)]\\s*|"
                    + "第[一二三四五六七八九十百千\\d]+条\\s*|步骤\\s*\\d+(?:\\s|$)).*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern GUIDE_PATTERN = Pattern.compile(
            ".*(?:如下|包括|下列|以下|见下表|步骤|代码|示例)(?:内容|项目|步骤)?\\s*[：:]?\\s*$");

    private final TokenCounter tokenCounter;

    public MarkdownSemanticUnitBuilder(TokenCounter tokenCounter) {
        this.tokenCounter = Objects.requireNonNull(tokenCounter, "tokenCounter");
    }

    public List<SemanticUnit> build(ParsedStructure structure) {
        Objects.requireNonNull(structure, "structure");
        List<SemanticUnit> units = new ArrayList<>();
        BoundarySignal pendingSignal = new BoundarySignal("DOCUMENT_START", 0);
        List<StructuredBlock> blocks = structure.blocks();

        for (int index = 0; index < blocks.size(); index++) {
            StructuredBlock block = blocks.get(index);
            if (block.type() == BlockType.HEADING) {
                pendingSignal = headingSignal(block.headingLevel());
                continue;
            }
            if (block.type() == BlockType.THEMATIC_BREAK) {
                pendingSignal = new BoundarySignal("THEMATIC_BREAK", MarkdownChunkPlanningStrategy.THEMATIC_BREAK);
                continue;
            }
            if (block.rawText() == null || block.rawText().isBlank()) {
                continue;
            }

            List<StructuredBlock> unitBlocks = new ArrayList<>();
            unitBlocks.add(block);
            if (isGuide(block) && index + 1 < blocks.size()) {
                StructuredBlock next = blocks.get(index + 1);
                if (isContainer(next) && block.sectionPath().equals(next.sectionPath())) {
                    unitBlocks.add(next);
                    index++;
                }
            }

            BoundarySignal start = peerLabel(block)
                    ? strongest(pendingSignal, new BoundarySignal("PEER_LABEL", MarkdownChunkPlanningStrategy.PEER_LABEL))
                    : pendingSignal;
            String content = render(unitBlocks);
            StructuredBlock last = unitBlocks.get(unitBlocks.size() - 1);
            BoundarySignal end = endSignal(last);
            units.add(new SemanticUnit(
                    "markdown-unit-" + (units.size() + 1),
                    unitBlocks,
                    block.sectionPath(),
                    tokenCounter.count(content),
                    Map.of(
                            "start", start.reason(),
                            "startScore", start.score(),
                            "end", end.reason(),
                            "endScore", end.score())));
            pendingSignal = new BoundarySignal("PARAGRAPH_END", MarkdownChunkPlanningStrategy.PARAGRAPH_END);
        }
        return List.copyOf(units);
    }

    static String render(List<StructuredBlock> blocks) {
        return blocks.stream().map(StructuredBlock::rawText).filter(Objects::nonNull)
                .filter(text -> !text.isBlank()).reduce((left, right) -> left + "\n\n" + right).orElse("");
    }

    private BoundarySignal headingSignal(Integer level) {
        if (level != null && level <= 2) {
            return new BoundarySignal("H" + level + "_SECTION", MarkdownChunkPlanningStrategy.H1_H2);
        }
        return new BoundarySignal("H" + (level == null ? "3" : level) + "_SECTION",
                MarkdownChunkPlanningStrategy.H3_H4);
    }

    private BoundarySignal endSignal(StructuredBlock block) {
        if (isContainer(block)) {
            return new BoundarySignal("CONTAINER_END", MarkdownChunkPlanningStrategy.CONTAINER_END);
        }
        return new BoundarySignal("PARAGRAPH_END", MarkdownChunkPlanningStrategy.PARAGRAPH_END);
    }

    private BoundarySignal strongest(BoundarySignal left, BoundarySignal right) {
        return left.score() >= right.score() ? left : right;
    }

    private boolean peerLabel(StructuredBlock block) {
        return block.type() == BlockType.PARAGRAPH
                && PEER_LABEL_PATTERN.matcher(block.plainText() == null ? "" : block.plainText().strip()).matches();
    }

    private boolean isGuide(StructuredBlock block) {
        return block.type() == BlockType.PARAGRAPH
                && GUIDE_PATTERN.matcher(block.plainText() == null ? "" : block.plainText().strip()).matches();
    }

    private boolean isContainer(StructuredBlock block) {
        return switch (block.type()) {
            case ORDERED_LIST, UNORDERED_LIST, TABLE, FENCED_CODE, INDENTED_CODE -> true;
            default -> false;
        };
    }

    private record BoundarySignal(String reason, int score) {
    }
}
