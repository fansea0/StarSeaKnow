package com.starsea.ai.chunking.markdown;

import com.starsea.ai.chunking.model.ChunkDraft;
import com.starsea.ai.chunking.model.ChunkPlan;
import com.starsea.ai.chunking.model.ChunkPolicy;
import com.starsea.ai.chunking.model.ChunkType;
import com.starsea.ai.chunking.model.ContextConfig;
import com.starsea.ai.chunking.model.BlockType;
import com.starsea.ai.chunking.model.ParentChildPolicy;
import com.starsea.ai.chunking.model.ParsedStructure;
import com.starsea.ai.chunking.model.PlannedChunk;
import com.starsea.ai.chunking.model.SourceLocator;
import com.starsea.ai.chunking.model.StructuredBlock;
import com.starsea.ai.chunking.registry.ChunkStrategyDescriptor;
import com.starsea.ai.chunking.spi.ChunkPlanningStrategy;
import com.starsea.ai.chunking.spi.TokenCounter;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Markdown planner that indexes child chunks while retaining larger source-owned parents. */
@Component
public final class MarkdownParentChildPlanningStrategy implements ChunkPlanningStrategy {

    private static final String CODE = "PARENT_CHILD";
    private static final String VERSION = "markdown-parent-child-v1";
    private static final Set<String> SUPPORTED_TYPES = Set.of("md", "markdown");

    private final TokenCounter tokenCounter;
    private final MarkdownChunkPlanningStrategy childPlanner;

    public MarkdownParentChildPlanningStrategy(TokenCounter tokenCounter) {
        this.tokenCounter = Objects.requireNonNull(tokenCounter, "tokenCounter");
        this.childPlanner = new MarkdownChunkPlanningStrategy(tokenCounter);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Set<String> supportedFileTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public String plannerVersion() {
        return VERSION;
    }

    @Override
    public ChunkStrategyDescriptor descriptor() {
        ParentChildPolicy defaults = ParentChildPolicy.defaults();
        return new ChunkStrategyDescriptor(CODE, "FILE_TYPE", SUPPORTED_TYPES, VERSION, List.of(
                new ChunkStrategyDescriptor.ConfigField("parentMode", "string", defaults.parentMode().name(),
                        null, null, Map.of("options", List.of("PARAGRAPH", "FULL_DOCUMENT"))),
                new ChunkStrategyDescriptor.ConfigField("parentMaxTokens", "number", defaults.parentMaxTokens(),
                        ParentChildPolicy.MIN_PARENT_TOKENS, ParentChildPolicy.MAX_PARENT_TOKENS, Map.of()),
                new ChunkStrategyDescriptor.ConfigField("childMaxTokens", "number", defaults.childMaxTokens(),
                        ParentChildPolicy.MIN_CHILD_TOKENS, ParentChildPolicy.MAX_CHILD_TOKENS, Map.of()),
                new ChunkStrategyDescriptor.ConfigField("childOverlapTokens", "number", defaults.childOverlapTokens(),
                        0, ParentChildPolicy.MAX_CHILD_OVERLAP_TOKENS, Map.of())),
                List.of(), ContextConfig.markdownDefaults());
    }

    @Override
    public List<ChunkDraft> plan(ParsedStructure structure, ChunkPolicy policy) {
        return childPlanner.plan(structure, policy);
    }

    @Override
    public Map<String, Object> normalizeConfig(Map<String, Object> config) {
        Map<String, Object> source = config == null ? Map.of() : config;
        ParentChildPolicy defaults = ParentChildPolicy.defaults();
        ParentChildPolicy policy = new ParentChildPolicy(
                source.containsKey("parentMode")
                        ? ParentChildPolicy.ParentMode.fromConfig(source.get("parentMode")) : defaults.parentMode(),
                integral(source.getOrDefault("parentMaxTokens", defaults.parentMaxTokens()), "parentMaxTokens"),
                integral(source.getOrDefault("childMaxTokens", defaults.childMaxTokens()), "childMaxTokens"),
                integral(source.getOrDefault("childOverlapTokens", defaults.childOverlapTokens()), "childOverlapTokens"));
        return Map.of(
                "parentMode", policy.parentMode().name(),
                "parentMaxTokens", policy.parentMaxTokens(),
                "childMaxTokens", policy.childMaxTokens(),
                "childOverlapTokens", policy.childOverlapTokens());
    }

    @Override
    public ChunkPlan planConfigured(ParsedStructure structure, Map<String, Object> config) {
        Objects.requireNonNull(structure, "structure");
        Map<String, Object> normalized = normalizeConfig(config);
        ParentChildPolicy policy = new ParentChildPolicy(
                ParentChildPolicy.ParentMode.fromConfig(normalized.get("parentMode")),
                (int) normalized.get("parentMaxTokens"),
                (int) normalized.get("childMaxTokens"),
                (int) normalized.get("childOverlapTokens"));
        int childTarget = Math.max(1, (int) Math.floor(policy.childMaxTokens() * 0.8));
        int childMin = Math.max(1, (int) Math.floor(policy.childMaxTokens() * 0.25));
        List<ChunkDraft> children = childPlanner.plan(structure,
                new ChunkPolicy(childMin, childTarget, policy.childMaxTokens()));
        return policy.parentMode() == ParentChildPolicy.ParentMode.FULL_DOCUMENT
                ? fullDocumentPlan(structure, children, policy)
                : paragraphPlan(structure, children, policy);
    }

    private ChunkPlan paragraphPlan(ParsedStructure structure, List<ChunkDraft> children,
                                    ParentChildPolicy policy) {
        List<PlannedChunk> output = new ArrayList<>();
        List<ChunkDraft> group = new ArrayList<>();
        int parentPosition = 0;
        for (ChunkDraft child : children) {
            if (!group.isEmpty() && (!samePath(group.get(0), child) || strongStart(child)
                    || parentTokens(structure, group, child) > policy.parentMaxTokens())) {
                addParagraphGroup(output, structure, group, parentPosition++, policy);
                group.clear();
            }
            group.add(child);
        }
        if (!group.isEmpty()) {
            addParagraphGroup(output, structure, group, parentPosition, policy);
        }
        return new ChunkPlan(output, policy.childMaxTokens());
    }

    private ChunkPlan fullDocumentPlan(ParsedStructure structure, List<ChunkDraft> children, ParentChildPolicy policy) {
        if (children.isEmpty()) {
            return new ChunkPlan(List.of(), policy.childMaxTokens());
        }
        List<StructuredBlock> blocks = structure.blocks().stream()
                .filter(block -> block.rawText() != null && !block.rawText().isBlank()).toList();
        String content = blocks.stream().map(StructuredBlock::rawText).reduce((left, right) -> left + "\n\n" + right)
                .orElseThrow(() -> new IllegalArgumentException("The source document produced no parent content"));
        ChunkDraft parentDraft = new ChunkDraft(List.of(), content, combineBlockLocators(blocks), tokenCounter.count(content),
                Map.of("start", "DOCUMENT_START", "end", "DOCUMENT_END"));
        List<PlannedChunk> output = new ArrayList<>();
        String parentKey = "parent-0";
        output.add(new PlannedChunk(parentKey, null, ChunkType.PARENT, 0, parentDraft, false, 40));
        for (int index = 0; index < children.size(); index++) {
            output.add(child("child-" + index, parentKey, index, children.get(index), policy));
        }
        return new ChunkPlan(output, policy.childMaxTokens());
    }

    private void addParagraphGroup(List<PlannedChunk> output, ParsedStructure structure,
                                   List<ChunkDraft> group, int parentPosition, ParentChildPolicy policy) {
        String parentKey = "parent-" + parentPosition;
        List<StructuredBlock> contextBlocks = sectionHeadingContext(structure, group.get(0));
        output.add(new PlannedChunk(parentKey, null, ChunkType.PARENT, parentPosition,
                parentDraft(group, contextBlocks), false, 40));
        for (int index = 0; index < group.size(); index++) {
            output.add(child("child-" + parentPosition + "-" + index, parentKey, index, group.get(index), policy));
        }
    }

    private PlannedChunk child(String key, String parentKey, int position, ChunkDraft draft,
                               ParentChildPolicy policy) {
        boolean overlapEnabled = policy.childOverlapTokens() > 0;
        return new PlannedChunk(key, parentKey, ChunkType.CHILD, position, draft, overlapEnabled,
                Math.max(policy.childOverlapTokens(), 1));
    }

    private ChunkDraft parentDraft(List<ChunkDraft> children, List<StructuredBlock> contextBlocks) {
        ChunkDraft first = children.get(0);
        ChunkDraft last = children.get(children.size() - 1);
        String childContent = children.stream().map(ChunkDraft::content)
                .reduce((left, right) -> left + "\n\n" + right).orElse("");
        String sectionContext = contextBlocks.stream().map(StructuredBlock::rawText)
                .filter(text -> text != null && !text.isBlank())
                .reduce((left, right) -> left + "\n\n" + right).orElse("");
        String content = sectionContext == null || sectionContext.isBlank()
                ? childContent : sectionContext + "\n\n" + childContent;
        List<SourceLocator> locators = new ArrayList<>();
        locators.addAll(contextBlocks.stream().map(StructuredBlock::sourceLocator)
                .filter(Objects::nonNull).toList());
        locators.addAll(children.stream().map(ChunkDraft::sourceLocator)
                .filter(Objects::nonNull).toList());
        return new ChunkDraft(first.sectionPath(), content, combineLocators(locators), tokenCounter.count(content),
                Map.of("start", first.boundaryReason().getOrDefault("start", "PARAGRAPH_END"),
                        "end", last.boundaryReason().getOrDefault("end", "PARAGRAPH_END")));
    }

    private int parentTokens(ParsedStructure structure, List<ChunkDraft> existing, ChunkDraft next) {
        String content = existing.stream().map(ChunkDraft::content)
                .reduce((left, right) -> left + "\n\n" + right)
                .map(current -> current + "\n\n" + next.content())
                .orElse(next.content());
        String context = sectionContext(structure, existing.get(0));
        if (!context.isBlank()) {
            content = context + "\n\n" + content;
        }
        return tokenCounter.count(MarkdownChunkPlanningStrategy.previewIndexText(
                existing.get(0).sectionPath(), content));
    }

    private String sectionContext(ParsedStructure structure, ChunkDraft child) {
        return sectionHeadingContext(structure, child).stream().map(StructuredBlock::rawText)
                .filter(text -> text != null && !text.isBlank())
                .reduce((left, right) -> left + "\n\n" + right).orElse("");
    }

    private List<StructuredBlock> sectionHeadingContext(ParsedStructure structure, ChunkDraft child) {
        List<String> path = child.sectionPath();
        if (path == null || path.isEmpty()) {
            return List.of();
        }
        int firstChildIndex = firstChildBlockIndex(structure, child);
        List<StructuredBlock> headings = new ArrayList<>();
        for (int depth = 1; depth <= path.size(); depth++) {
            List<String> prefix = path.subList(0, depth);
            StructuredBlock selected = null;
            for (int index = 0; index < firstChildIndex; index++) {
                StructuredBlock block = structure.blocks().get(index);
                if (block.type() == BlockType.HEADING
                        && block.sectionPath().equals(prefix)) {
                    selected = block;
                }
            }
            if (selected != null) {
                headings.add(selected);
            }
        }
        return List.copyOf(headings);
    }

    private int firstChildBlockIndex(ParsedStructure structure, ChunkDraft child) {
        if (child.sourceLocator() == null || child.sourceLocator().blockIds().isEmpty()) {
            return structure.blocks().size();
        }
        String firstBlockId = child.sourceLocator().blockIds().get(0);
        for (int index = 0; index < structure.blocks().size(); index++) {
            if (firstBlockId.equals(structure.blocks().get(index).blockId())) {
                return index;
            }
        }
        return structure.blocks().size();
    }

    private boolean samePath(ChunkDraft first, ChunkDraft second) {
        return first.sectionPath().equals(second.sectionPath());
    }

    private boolean strongStart(ChunkDraft chunk) {
        Object value = chunk.boundaryReason().get("start");
        String start = value == null ? "" : value.toString();
        return start.equals("THEMATIC_BREAK") || start.equals("PEER_LABEL") || start.matches("H[1-4]_SECTION");
    }

    private SourceLocator combineDraftLocators(List<ChunkDraft> drafts) {
        return combineLocators(drafts.stream().map(ChunkDraft::sourceLocator).filter(Objects::nonNull).toList());
    }

    private SourceLocator combineBlockLocators(List<StructuredBlock> blocks) {
        return combineLocators(blocks.stream().map(StructuredBlock::sourceLocator).filter(Objects::nonNull).toList());
    }

    private SourceLocator combineLocators(List<SourceLocator> locators) {
        if (locators.isEmpty()) {
            return null;
        }
        SourceLocator first = locators.get(0);
        SourceLocator last = locators.get(locators.size() - 1);
        Set<String> ids = new LinkedHashSet<>();
        List<Map<String, Object>> regions = new ArrayList<>();
        for (SourceLocator locator : locators) {
            ids.addAll(locator.blockIds());
            regions.addAll(locator.regions());
        }
        return new SourceLocator(first.type(), List.copyOf(ids), first.startOffset(), last.endOffset(),
                first.startLine(), last.endLine(), first.startPage(), last.endPage(), regions);
    }

    private int integral(Object value, String field) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(field + " must be an integral number");
        }
        try {
            return new BigDecimal(number.toString()).intValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException(field + " must be an integral number", exception);
        }
    }
}
