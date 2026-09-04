package com.starsea.ai.chunking.general;

import com.starsea.ai.chunking.context.ChunkIndexContentBuilder;
import com.starsea.ai.chunking.model.BoundaryReason;
import com.starsea.ai.chunking.model.ChunkDraft;
import com.starsea.ai.chunking.model.ChunkPlanningRequest;
import com.starsea.ai.chunking.model.ChunkPlanningResult;
import com.starsea.ai.chunking.model.ContextConfig;
import com.starsea.ai.chunking.model.GeneralChunkConfig;
import com.starsea.ai.chunking.model.SourceLocator;
import com.starsea.ai.chunking.model.StructuredBlock;
import com.starsea.ai.chunking.registry.ChunkStrategyDescriptor;
import com.starsea.ai.chunking.spi.ChunkPlanningStrategy;
import com.starsea.ai.chunking.spi.TokenCounter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministic delimiter-oriented planner with code-point-safe fallback splitting. */
@Component
public final class GeneralChunkPlanningStrategy implements ChunkPlanningStrategy {
    private static final String CODE = "GENERAL";
    private static final String VERSION = "general-deterministic-v1";
    private static final Set<String> SUPPORTED_TYPES = Set.of("*");
    private static final int OVERLAP_FORMAT_CODE_POINTS = UnicodeText.length("上文：\n\n");

    private final TokenCounter tokenCounter;

    public GeneralChunkPlanningStrategy(TokenCounter tokenCounter) {
        this.tokenCounter = Objects.requireNonNull(tokenCounter, "tokenCounter");
    }

    @Override public String code() { return CODE; }
    @Override public Set<String> supportedFileTypes() { return SUPPORTED_TYPES; }
    @Override public String plannerVersion() { return VERSION; }

    @Override
    public ChunkStrategyDescriptor descriptor() {
        GeneralChunkConfig defaults = GeneralChunkConfig.defaults();
        return new ChunkStrategyDescriptor(CODE, "GLOBAL", SUPPORTED_TYPES, VERSION,
                List.of(
                        field("delimiter", "string", defaults.delimiter(), null, 256, Map.of()),
                        field("delimiterMode", "enum", defaults.delimiterMode().name(), null, null,
                                Map.of("values", List.of("LITERAL", "REGEX"))),
                        field("maxCharacters", "number", defaults.maxCharacters(),
                                GeneralChunkConfig.MIN_CHARACTERS, GeneralChunkConfig.MAX_CHARACTERS, Map.of()),
                        field("collapseWhitespace", "boolean", defaults.collapseWhitespace(), null, null, Map.of()),
                        field("removeUrls", "boolean", defaults.removeUrls(), null, null, Map.of()),
                        field("removeEmails", "boolean", defaults.removeEmails(), null, null, Map.of())),
                ChunkStrategyDescriptor.contextConfigFields(ContextConfig.generalDefaults()),
                ContextConfig.generalDefaults());
    }

    @Override
    public ChunkPlanningResult plan(ChunkPlanningRequest request) {
        Objects.requireNonNull(request, "request");
        if (!(request.strategyConfig() instanceof GeneralChunkConfig config)) {
            throw new IllegalArgumentException("General planner requires GeneralChunkConfig");
        }
        int laterBudget = laterBodyBudget(config, request.contextConfig());
        List<ChunkDraft> drafts = new ArrayList<>();
        Accumulator current = null;
        String nextStartReason = BoundaryReason.DOCUMENT_START;

        List<StructuredBlock> blocks = request.structure().blocks();
        for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
            StructuredBlock block = blocks.get(blockIndex);
            String text = block.plainText() == null ? "" : block.plainText();
            if (text.isEmpty()) continue;
            String boundaryAfter = boundaryAfter(block, blockIndex + 1 == blocks.size());

            String withSeparator = current == null ? text : current.content + "\n" + text;
            int budget = drafts.isEmpty() ? config.maxCharacters() : laterBudget;
            if (current != null && fits(withSeparator, budget, request.maxIndexTokens())) {
                current.append("\n" + text, block.sourceLocator(), boundaryAfter);
                continue;
            }
            if (current != null) {
                drafts.add(current.toDraft());
                nextStartReason = String.valueOf(drafts.get(drafts.size() - 1).boundaryReason().get("end"));
                current = null;
            }

            UnicodeText.CodePointIndex textIndex = UnicodeText.index(text);
            int consumedCodePoints = 0;
            int totalCodePoints = textIndex.length();
            while (consumedCodePoints < totalCodePoints) {
                budget = drafts.isEmpty() ? config.maxCharacters() : laterBudget;
                int remainingCodePoints = totalCodePoints - consumedCodePoints;
                int characterMaximum = Math.min(budget, remainingCodePoints);
                String candidate = textIndex.substring(
                        consumedCodePoints, consumedCodePoints + characterMaximum);
                if (remainingCodePoints <= budget && fits(candidate, budget, request.maxIndexTokens())) {
                    SourceLocator locator = sliceLocator(block, textIndex,
                            consumedCodePoints, totalCodePoints);
                    current = new Accumulator(candidate, locator, nextStartReason, boundaryAfter);
                    consumedCodePoints = totalCodePoints;
                    continue;
                }

                int tokenMaximum = maximumTokenPrefix(candidate, characterMaximum, request.maxIndexTokens());
                if (tokenMaximum <= 0) {
                    throw new IllegalArgumentException("maxIndexTokens cannot hold one Unicode code point");
                }
                boolean tokenLimited = tokenMaximum < characterMaximum
                        || tokenCounter.count(candidate) > request.maxIndexTokens();
                BoundaryUnit split = GeneralBoundaryScanner.scanFallback(candidate, tokenMaximum, null);
                String endReason = tokenLimited ? BoundaryReason.MODEL_TOKEN_LIMIT : split.boundaryAfter().name();
                int splitEnd = consumedCodePoints + split.cleanedEnd();
                String content = textIndex.substring(consumedCodePoints, splitEnd);
                SourceLocator locator = sliceLocator(block, textIndex, consumedCodePoints, splitEnd);
                drafts.add(draft(content, locator, nextStartReason, endReason,
                        split.boundaryAfter() == BoundaryKind.FORCED_CHARACTER));
                consumedCodePoints = splitEnd;
                nextStartReason = endReason;
            }
        }
        if (current != null) drafts.add(current.toDraft());

        drafts = rebalanceBlankDrafts(drafts, config.maxCharacters(), laterBudget,
                request.maxIndexTokens());

        validateDrafts(drafts, config.maxCharacters(), laterBudget, request.maxIndexTokens());
        int forced = Math.toIntExact(drafts.stream()
                .filter(draft -> Boolean.TRUE.equals(draft.boundaryReason().get("forcedSplit"))).count());
        int tokenLimited = Math.toIntExact(drafts.stream()
                .filter(draft -> BoundaryReason.MODEL_TOKEN_LIMIT.equals(draft.boundaryReason().get("end"))).count());
        return new ChunkPlanningResult(drafts, forced, tokenLimited);
    }

    private int laterBodyBudget(GeneralChunkConfig config, ContextConfig context) {
        int reserved = context.enabled() ? context.limit() + OVERLAP_FORMAT_CODE_POINTS : 0;
        int budget = config.maxCharacters() - reserved;
        if (budget < 1) {
            throw new IllegalArgumentException("GENERAL overlap leaves no useful body budget");
        }
        return budget;
    }

    private int maximumTokenPrefix(String text, int maximumCodePoints, int maxTokens) {
        UnicodeText.CodePointIndex index = UnicodeText.index(text);
        if (tokenCounter.count(index.substring(0, maximumCodePoints)) <= maxTokens) return maximumCodePoints;
        int low = 0;
        int high = maximumCodePoints;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (tokenCounter.count(index.substring(0, middle)) <= maxTokens) low = middle;
            else high = middle - 1;
        }
        return low;
    }

    private boolean fits(String body, int maxCharacters, int maxTokens) {
        return UnicodeText.length(body) <= maxCharacters
                && tokenCounter.count(ChunkIndexContentBuilder.preview(List.of(), body)) <= maxTokens;
    }

    private ChunkDraft draft(String content, SourceLocator locator, String start, String end, boolean forced) {
        return new ChunkDraft(List.of(), content, locator, tokenCounter.count(content),
                new BoundaryReason(start, end, forced).asMap());
    }

    private String boundaryAfter(StructuredBlock block, boolean finalBlock) {
        Object value = block.attributes().get("boundaryAfter");
        if (value instanceof BoundaryKind kind) return kind.name();
        if (value != null) return value.toString();
        return finalBlock ? BoundaryReason.DOCUMENT_END : BoundaryKind.USER_DELIMITER.name();
    }

    private SourceLocator sliceLocator(StructuredBlock block, UnicodeText.CodePointIndex textIndex,
                                       int codePointStart, int codePointEnd) {
        SourceLocator source = block.sourceLocator();
        if (source == null) return emptyLocator();
        int charStart = textIndex.charIndex(codePointStart);
        int charEnd = textIndex.charIndex(codePointEnd);
        Object mapping = block.attributes().get(CleanedSegment.OFFSET_MAP_ATTRIBUTE);
        Integer startOffset;
        Integer endOffset;
        if (mapping instanceof CleanedOffsetMap offsetMap && charStart < charEnd) {
            startOffset = offsetMap.sourceStart(charStart);
            endOffset = offsetMap.sourceEnd(charEnd);
        } else {
            startOffset = source.startOffset() == null ? null : source.startOffset() + charStart;
            endOffset = source.startOffset() == null ? source.endOffset() : source.startOffset() + charEnd;
        }
        return new SourceLocator(source.type(), source.blockIds(), startOffset, endOffset,
                source.startLine(), source.endLine(), source.startPage(), source.endPage(), source.regions());
    }

    private SourceLocator combineLocators(List<SourceLocator> locators) {
        if (locators.isEmpty()) return emptyLocator();
        SourceLocator first = locators.get(0);
        SourceLocator last = locators.get(locators.size() - 1);
        Set<String> blockIds = new LinkedHashSet<>();
        List<Map<String, Object>> regions = new ArrayList<>();
        for (SourceLocator locator : locators) {
            if (locator == null) continue;
            blockIds.addAll(locator.blockIds());
            regions.addAll(locator.regions());
        }
        return new SourceLocator(first.type(), List.copyOf(blockIds), first.startOffset(), last.endOffset(),
                first.startLine(), last.endLine(), first.startPage(), last.endPage(), regions);
    }

    private SourceLocator emptyLocator() {
        return new SourceLocator("TEXT", List.of(), null, null, null, null, null, null, List.of());
    }

    private List<ChunkDraft> rebalanceBlankDrafts(List<ChunkDraft> original, int firstBudget,
                                                   int laterBudget, int maxTokens) {
        List<ChunkDraft> result = new ArrayList<>(original);
        for (int index = 0; index < result.size(); index++) {
            ChunkDraft blank = result.get(index);
            if (!blank.content().isBlank()) continue;
            if (index == 0) {
                throw new IllegalArgumentException("Retained leading whitespace cannot form a useful chunk");
            }
            ChunkDraft previous = result.get(index - 1);
            String separator = BoundaryKind.USER_DELIMITER.name().equals(
                    previous.boundaryReason().get("end")) ? "\n" : "";
            String combined = previous.content() + separator + blank.content();
            int previousBudget = index - 1 == 0 ? firstBudget : laterBudget;
            int blankBudget = index == 0 ? firstBudget : laterBudget;
            if (fits(combined, previousBudget, maxTokens)) {
                result.set(index - 1, draft(combined,
                        combineLocators(List.of(previous.sourceLocator(), blank.sourceLocator())),
                        boundaryValue(previous, "start"), boundaryValue(blank, "end"), false));
                result.remove(index--);
                continue;
            }

            UnicodeText.CodePointIndex previousText = UnicodeText.index(previous.content());
            int split = Math.min(previousText.length() - 1, previousBudget);
            boolean rebalanced = false;
            while (split > 0) {
                String prefix = previousText.substring(0, split);
                String suffix = previousText.substring(split, previousText.length())
                        + separator + blank.content();
                if (!prefix.isBlank() && !suffix.isBlank()
                        && fits(prefix, previousBudget, maxTokens)
                        && fits(suffix, blankBudget, maxTokens)) {
                    SourceLocator prefixLocator = sliceDraftLocator(previous, previousText, 0, split);
                    SourceLocator movedLocator = sliceDraftLocator(
                            previous, previousText, split, previousText.length());
                    SourceLocator suffixLocator = combineLocators(
                            List.of(movedLocator, blank.sourceLocator()));
                    result.set(index - 1, draft(prefix, prefixLocator,
                            boundaryValue(previous, "start"),
                            BoundaryKind.FORCED_CHARACTER.name(), true));
                    result.set(index, draft(suffix, suffixLocator,
                            BoundaryKind.FORCED_CHARACTER.name(), boundaryValue(blank, "end"), false));
                    rebalanced = true;
                    break;
                }
                split--;
            }
            if (!rebalanced) {
                throw new IllegalArgumentException(
                        "Retained whitespace cannot be packed into useful chunks within the configured limits");
            }
        }
        return result;
    }

    private SourceLocator sliceDraftLocator(ChunkDraft draft, UnicodeText.CodePointIndex text,
                                            int codePointStart, int codePointEnd) {
        SourceLocator source = draft.sourceLocator();
        if (source == null) return emptyLocator();
        int charStart = text.charIndex(codePointStart);
        int charEnd = text.charIndex(codePointEnd);
        Integer start = source.startOffset() == null ? null : source.startOffset() + charStart;
        Integer end = source.startOffset() == null ? source.endOffset() : source.startOffset() + charEnd;
        return new SourceLocator(source.type(), source.blockIds(), start, end,
                source.startLine(), source.endLine(), source.startPage(), source.endPage(), source.regions());
    }

    private String boundaryValue(ChunkDraft draft, String key) {
        Object value = draft.boundaryReason().get(key);
        return value == null ? BoundaryReason.DOCUMENT_END : value.toString();
    }

    private void validateDrafts(List<ChunkDraft> drafts, int firstBudget, int laterBudget, int maxTokens) {
        for (int index = 0; index < drafts.size(); index++) {
            ChunkDraft draft = drafts.get(index);
            int budget = index == 0 ? firstBudget : laterBudget;
            if (draft.content().isBlank() || !draft.sectionPath().isEmpty()
                    || UnicodeText.length(draft.content()) > budget
                    || tokenCounter.count(ChunkIndexContentBuilder.preview(List.of(), draft.content())) > maxTokens) {
                throw new IllegalStateException("General planner produced an invalid chunk");
            }
        }
    }

    private ChunkStrategyDescriptor.ConfigField field(String key, String type, Object defaultValue,
                                                       Integer min, Integer max, Map<String, Object> attributes) {
        return new ChunkStrategyDescriptor.ConfigField(key, type, defaultValue, min, max, attributes);
    }

    private final class Accumulator {
        private String content;
        private final List<SourceLocator> locators = new ArrayList<>();
        private final String startReason;
        private String endReason;

        private Accumulator(String content, SourceLocator locator, String startReason, String endReason) {
            this.content = content;
            this.locators.add(locator == null ? emptyLocator() : locator);
            this.startReason = startReason;
            this.endReason = endReason;
        }

        private void append(String suffix, SourceLocator locator, String boundaryAfter) {
            content += suffix;
            locators.add(locator == null ? emptyLocator() : locator);
            endReason = boundaryAfter;
        }

        private ChunkDraft toDraft() {
            return draft(content, combineLocators(locators), startReason, endReason, false);
        }
    }
}
