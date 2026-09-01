package com.starsea.ai.chunking.markdown;

import com.starsea.ai.chunking.model.BoundaryReason;
import com.starsea.ai.chunking.model.ChunkDraft;
import com.starsea.ai.chunking.model.ChunkPolicy;
import com.starsea.ai.chunking.model.ParsedStructure;
import com.starsea.ai.chunking.model.SemanticUnit;
import com.starsea.ai.chunking.model.SourceLocator;
import com.starsea.ai.chunking.registry.ChunkStrategyDescriptor;
import com.starsea.ai.chunking.spi.ChunkPlanningStrategy;
import com.starsea.ai.chunking.spi.TokenCounter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Markdown-aware adaptive planner that owns source boundaries but never overlap. */
@Component
public final class MarkdownChunkPlanningStrategy implements ChunkPlanningStrategy {

    static final int H1_H2 = 100;
    static final int H3_H4 = 90;
    static final int THEMATIC_BREAK = 90;
    static final int PEER_LABEL = 85;
    static final int CONTAINER_END = 70;
    static final int PARAGRAPH_END = 50;
    static final int SENTENCE_END = 25;

    private static final String CODE = "MARKDOWN_OPTIMIZED";
    private static final String VERSION = "markdown-adaptive-v1";
    private static final Set<String> SUPPORTED_TYPES = Set.of("md", "markdown");

    private final TokenCounter tokenCounter;
    private final MarkdownSemanticUnitBuilder unitBuilder;
    private final MarkdownRecursiveSplitter recursiveSplitter;

    public MarkdownChunkPlanningStrategy(TokenCounter tokenCounter) {
        this.tokenCounter = Objects.requireNonNull(tokenCounter, "tokenCounter");
        this.unitBuilder = new MarkdownSemanticUnitBuilder(tokenCounter);
        this.recursiveSplitter = new MarkdownRecursiveSplitter(tokenCounter);
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
        return new ChunkStrategyDescriptor(
                CODE,
                "FILE_TYPE",
                SUPPORTED_TYPES,
                VERSION,
                List.of(
                        integerField("minTokens", ChunkPolicy.defaults().minTokens(), 1, ChunkPolicy.MAX_ALLOWED_TOKENS),
                        integerField("targetTokens", ChunkPolicy.defaults().targetTokens(), 1, ChunkPolicy.MAX_ALLOWED_TOKENS),
                        integerField("maxTokens", ChunkPolicy.defaults().maxTokens(), 1, ChunkPolicy.MAX_ALLOWED_TOKENS)));
    }

    @Override
    public List<ChunkDraft> plan(ParsedStructure structure, ChunkPolicy policy) {
        Objects.requireNonNull(structure, "structure");
        Objects.requireNonNull(policy, "policy");
        List<ChunkDraft> planned = new ArrayList<>();
        List<PlanningAtom> segment = new ArrayList<>();

        for (SemanticUnit unit : unitBuilder.build(structure)) {
            String content = MarkdownSemanticUnitBuilder.render(unit.blocks());
            if (!fits(unit.sectionPath(), content, policy.maxTokens())) {
                flushSegment(segment, policy, planned);
                for (MarkdownRecursiveSplitter.SplitPart part : recursiveSplitter.split(unit, policy.maxTokens())) {
                    planned.add(draft(
                            unit.sectionPath(),
                            part.content(),
                            part.sourceLocator(),
                            attributeString(unit, "start", "PARAGRAPH_END"),
                            part.endReason(),
                            true));
                }
                continue;
            }

            PlanningAtom atom = atom(unit, content);
            if (!segment.isEmpty() && startsNewSegment(segment.get(segment.size() - 1), atom)) {
                flushSegment(segment, policy, planned);
            }
            segment.add(atom);
        }
        flushSegment(segment, policy, planned);

        List<ChunkDraft> merged = mergeShortTails(planned, policy);
        for (ChunkDraft chunk : merged) {
            if (chunk.content().isBlank() || chunk.tokenCount() > policy.maxTokens()) {
                throw new IllegalStateException("Markdown planner produced an invalid chunk");
            }
        }
        return List.copyOf(merged);
    }

    static String previewIndexText(List<String> path, String content) {
        return path.isEmpty() ? content : "标题：" + String.join(" > ", path) + "\n\n" + content;
    }

    private void flushSegment(List<PlanningAtom> segment, ChunkPolicy policy, List<ChunkDraft> output) {
        if (segment.isEmpty()) {
            return;
        }
        int start = 0;
        while (start < segment.size()) {
            int farthest = farthestFitting(segment, start, policy.maxTokens());
            if (farthest < start) {
                throw new IllegalStateException("A semantic unit exceeded maxTokens without recursive splitting");
            }

            int end;
            boolean remainingFits = farthest == segment.size() - 1;
            int allTokens = count(segment, start, farthest);
            if (remainingFits && allTokens <= policy.targetTokens()) {
                end = farthest;
            } else if (!remainingFits) {
                end = farthest;
            } else {
                end = bestScoredBoundary(segment, start, farthest, policy);
            }
            output.add(combine(segment, start, end));
            start = end + 1;
        }
        segment.clear();
    }

    private int farthestFitting(List<PlanningAtom> atoms, int start, int maxTokens) {
        int farthest = start - 1;
        for (int end = start; end < atoms.size(); end++) {
            if (!fits(atoms.get(start).path(), render(atoms, start, end), maxTokens)) {
                break;
            }
            farthest = end;
        }
        return farthest;
    }

    private int bestScoredBoundary(List<PlanningAtom> atoms, int start, int farthest, ChunkPolicy policy) {
        List<BoundaryCandidate> candidates = new ArrayList<>();
        for (int end = start; end < farthest; end++) {
            int tokens = count(atoms, start, end);
            if (tokens >= policy.minTokens()) {
                PlanningAtom current = atoms.get(end);
                PlanningAtom next = atoms.get(end + 1);
                int score = Math.max(current.endScore(), next.startScore());
                candidates.add(new BoundaryCandidate(end, score, Math.abs(tokens - policy.targetTokens())));
            }
        }
        return candidates.stream()
                .sorted(Comparator.comparingInt(BoundaryCandidate::score).reversed()
                        .thenComparingInt(BoundaryCandidate::distance)
                        .thenComparing(Comparator.comparingInt(BoundaryCandidate::end).reversed()))
                .map(BoundaryCandidate::end)
                .findFirst()
                .orElse(farthest);
    }

    private ChunkDraft combine(List<PlanningAtom> atoms, int start, int end) {
        PlanningAtom first = atoms.get(start);
        PlanningAtom last = atoms.get(end);
        List<String> blockIds = new ArrayList<>();
        List<SourceLocator> locators = new ArrayList<>();
        for (int index = start; index <= end; index++) {
            SourceLocator locator = atoms.get(index).sourceLocator();
            blockIds.addAll(locator.blockIds());
            locators.add(locator);
        }
        SourceLocator source = combineLocators(locators, blockIds);
        String endReason = last.endReason();
        if (end + 1 < atoms.size()) {
            PlanningAtom next = atoms.get(end + 1);
            if (next.startScore() > last.endScore()) {
                endReason = next.startReason();
            }
        }
        return draft(first.path(), render(atoms, start, end), source,
                first.startReason(), endReason, false);
    }

    private List<ChunkDraft> mergeShortTails(List<ChunkDraft> chunks, ChunkPolicy policy) {
        List<ChunkDraft> merged = new ArrayList<>();
        for (ChunkDraft current : chunks) {
            if (!merged.isEmpty() && current.tokenCount() < policy.minTokens()) {
                ChunkDraft previous = merged.get(merged.size() - 1);
                String combinedContent = previous.content() + "\n\n" + current.content();
                if (previous.sectionPath().equals(current.sectionPath())
                        && !isStrongStart(current)
                        && fits(current.sectionPath(), combinedContent, policy.maxTokens())) {
                    List<String> blockIds = new ArrayList<>(previous.sourceLocator().blockIds());
                    blockIds.addAll(current.sourceLocator().blockIds());
                    SourceLocator locator = combineLocators(
                            List.of(previous.sourceLocator(), current.sourceLocator()), blockIds);
                    boolean forced = Boolean.TRUE.equals(previous.boundaryReason().get("forcedSplit"))
                            || Boolean.TRUE.equals(current.boundaryReason().get("forcedSplit"));
                    merged.set(merged.size() - 1, draft(
                            previous.sectionPath(),
                            combinedContent,
                            locator,
                            String.valueOf(previous.boundaryReason().get("start")),
                            String.valueOf(current.boundaryReason().get("end")),
                            forced));
                    continue;
                }
            }
            merged.add(current);
        }
        return merged;
    }

    private boolean isStrongStart(ChunkDraft chunk) {
        String start = String.valueOf(chunk.boundaryReason().get("start"));
        return start.equals("THEMATIC_BREAK") || start.equals("PEER_LABEL") || start.matches("H[1-4]_SECTION");
    }

    private ChunkDraft draft(List<String> path, String content, SourceLocator locator,
                             String startReason, String endReason, boolean forcedSplit) {
        int tokens = tokenCounter.count(previewIndexText(path, content));
        return new ChunkDraft(path, content, locator, tokens,
                new BoundaryReason(startReason, endReason, forcedSplit).asMap());
    }

    private PlanningAtom atom(SemanticUnit unit, String content) {
        return new PlanningAtom(
                unit.sectionPath(),
                content,
                MarkdownRecursiveSplitter.sourceLocator(unit.blocks()),
                attributeString(unit, "start", "PARAGRAPH_END"),
                attributeInt(unit, "startScore", PARAGRAPH_END),
                attributeString(unit, "end", "PARAGRAPH_END"),
                attributeInt(unit, "endScore", PARAGRAPH_END));
    }

    private boolean startsNewSegment(PlanningAtom previous, PlanningAtom current) {
        if (!previous.path().equals(current.path())) {
            return true;
        }
        return current.startReason().equals("THEMATIC_BREAK")
                || current.startReason().matches("H[1-4]_SECTION");
    }

    private String render(List<PlanningAtom> atoms, int start, int end) {
        return atoms.subList(start, end + 1).stream().map(PlanningAtom::content)
                .reduce((left, right) -> left + "\n\n" + right).orElse("");
    }

    private int count(List<PlanningAtom> atoms, int start, int end) {
        return tokenCounter.count(previewIndexText(atoms.get(start).path(), render(atoms, start, end)));
    }

    private boolean fits(List<String> path, String content, int maxTokens) {
        return tokenCounter.count(previewIndexText(path, content)) <= maxTokens;
    }

    private ChunkStrategyDescriptor.ConfigField integerField(String key, int defaultValue, int min, int max) {
        return new ChunkStrategyDescriptor.ConfigField(key, "number", defaultValue, min, max, Map.of());
    }

    private String attributeString(SemanticUnit unit, String key, String defaultValue) {
        return String.valueOf(unit.attributes().getOrDefault(key, defaultValue));
    }

    private int attributeInt(SemanticUnit unit, String key, int defaultValue) {
        Object value = unit.attributes().get(key);
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    private SourceLocator combineLocators(List<SourceLocator> locators, List<String> blockIds) {
        SourceLocator first = locators.get(0);
        SourceLocator last = locators.get(locators.size() - 1);
        Set<String> uniqueBlockIds = new LinkedHashSet<>(blockIds);
        List<Map<String, Object>> regions = locators.stream()
                .flatMap(locator -> locator.regions().stream())
                .toList();
        return new SourceLocator(
                first.type(),
                List.copyOf(uniqueBlockIds),
                first.startOffset(),
                last.endOffset(),
                first.startLine(),
                last.endLine(),
                first.startPage(),
                last.endPage(),
                regions);
    }

    private record PlanningAtom(
            List<String> path,
            String content,
            SourceLocator sourceLocator,
            String startReason,
            int startScore,
            String endReason,
            int endScore) {
    }

    private record BoundaryCandidate(int end, int score, int distance) {
    }
}
