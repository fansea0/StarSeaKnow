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
        List<PlannedDraft> drafts = new ArrayList<>();
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
                current.append(mappedFragment(block, UnicodeText.index(text),
                        0, UnicodeText.length(text)), boundaryAfter);
                continue;
            }
            if (current != null) {
                drafts.add(current.toDraft());
                nextStartReason = String.valueOf(
                        drafts.get(drafts.size() - 1).boundaryReason().get("end"));
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
                    current = new Accumulator(mappedFragment(block, textIndex,
                            consumedCodePoints, totalCodePoints), nextStartReason, boundaryAfter);
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
                drafts.add(plannedDraft(List.of(mappedFragment(block, textIndex,
                                consumedCodePoints, splitEnd)), nextStartReason, endReason,
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
        return new ChunkPlanningResult(drafts.stream().map(PlannedDraft::toChunkDraft).toList(),
                forced, tokenLimited);
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

    private PlannedDraft plannedDraft(List<MappedFragment> fragments, String start, String end,
                                      boolean forced) {
        StringBuilder contentBuilder = new StringBuilder();
        fragments.forEach(fragment -> contentBuilder.append(fragment.text()));
        String content = contentBuilder.toString();
        SourceLocator locator = combineLocators(fragments.stream()
                .map(MappedFragment::sourceLocator).filter(Objects::nonNull).toList());
        return new PlannedDraft(List.copyOf(fragments), content, locator,
                tokenCounter.count(content),
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
        List<Map<String, Object>> regions = source.regions();
        Integer startPage = source.startPage();
        Integer endPage = source.endPage();
        Integer startLine = source.startLine();
        Integer endLine = source.endLine();
        Object mapped = block.attributes().get(CleanedSegment.SOURCE_REGIONS_ATTRIBUTE);
        if (mapped instanceof List<?> candidates && startOffset != null && endOffset != null) {
            List<Map<String, Object>> sliced = new ArrayList<>();
            Integer slicedStartPage = null;
            Integer slicedEndPage = null;
            Integer slicedStartLine = null;
            Integer slicedEndLine = null;
            for (Object candidate : candidates) {
                if (!(candidate instanceof MappedSourceRegion region)
                        || region.sourceEnd() <= startOffset || region.sourceStart() >= endOffset) {
                    continue;
                }
                sliced.add(region.region());
                Object page = region.region().get("page");
                if (page instanceof Number number) {
                    int value = number.intValue();
                    slicedStartPage = slicedStartPage == null ? value : Math.min(slicedStartPage, value);
                    slicedEndPage = slicedEndPage == null ? value : Math.max(slicedEndPage, value);
                }
                Integer regionStartLine = integer(region.region().get("startLine"));
                Integer regionEndLine = integer(region.region().get("endLine"));
                Integer line = integer(region.region().get("line"));
                if (regionStartLine == null) regionStartLine = line;
                if (regionEndLine == null) regionEndLine = line;
                if (regionStartLine != null) {
                    slicedStartLine = slicedStartLine == null ? regionStartLine
                            : Math.min(slicedStartLine, regionStartLine);
                }
                if (regionEndLine != null) {
                    slicedEndLine = slicedEndLine == null ? regionEndLine
                            : Math.max(slicedEndLine, regionEndLine);
                }
            }
            regions = List.copyOf(sliced);
            startPage = slicedStartPage;
            endPage = slicedEndPage;
            startLine = slicedStartLine;
            endLine = slicedEndLine;
        }
        return new SourceLocator(source.type(), source.blockIds(), startOffset, endOffset,
                startLine, endLine, startPage, endPage, regions);
    }

    private Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
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

    private List<PlannedDraft> rebalanceBlankDrafts(List<PlannedDraft> original, int firstBudget,
                                                     int laterBudget, int maxTokens) {
        List<PlannedDraft> result = new ArrayList<>(original);
        int index = 0;
        while (index < result.size()) {
            if (!UnicodeText.isBlank(result.get(index).content())) {
                index++;
                continue;
            }
            int blankEnd = index + 1;
            while (blankEnd < result.size()
                    && UnicodeText.isBlank(result.get(blankEnd).content())) {
                blankEnd++;
            }
            int rangeStart = index == 0 ? 0 : index - 1;
            int rangeEnd = blankEnd == result.size() ? blankEnd : blankEnd + 1;
            List<PlannedDraft> range = List.copyOf(result.subList(rangeStart, rangeEnd));
            MappedSequence sequence = materialize(range);
            List<PlannedDraft> replacement = repartition(sequence, rangeStart,
                    firstBudget, laterBudget, maxTokens,
                    boundaryValue(range.get(0), "start"),
                    boundaryValue(range.get(range.size() - 1), "end"));
            result.subList(rangeStart, rangeEnd).clear();
            result.addAll(rangeStart, replacement);
            // Repartitioning may legitimately leave whitespace-only chunks when a run is longer
            // than every body budget. The selected range already includes both available
            // neighbouring seeds, so revisiting it cannot improve the layout and would loop.
            index = rangeStart + replacement.size();
        }
        return result;
    }

    private MappedSequence materialize(List<PlannedDraft> drafts) {
        List<MappedFragment> fragments = new ArrayList<>();
        for (int index = 0; index < drafts.size(); index++) {
            PlannedDraft draft = drafts.get(index);
            fragments.addAll(draft.fragments());
            if (index + 1 < drafts.size()
                    && BoundaryKind.USER_DELIMITER.name().equals(
                    draft.boundaryReason().get("end"))) {
                fragments.add(MappedFragment.synthetic("\n"));
            }
        }
        return new MappedSequence(fragments);
    }

    private List<PlannedDraft> repartition(MappedSequence sequence, int globalStartIndex,
                                            int firstBudget, int laterBudget, int maxTokens,
                                            String outerStart, String outerEnd) {
        List<Integer> boundaries = new ArrayList<>();
        boundaries.add(0);
        int start = 0;
        while (start < sequence.length()) {
            int budget = globalStartIndex + boundaries.size() - 1 == 0
                    ? firstBudget : laterBudget;
            int end = maximumFittingEnd(sequence.index(), start, budget, maxTokens);
            if (end <= start) throw cannotPackWhitespace();
            if (end < sequence.length()) {
                int lastNonWhitespace = sequence.lastNonWhitespace();
                if (lastNonWhitespace < end) {
                    int adjusted = lastNonWhitespace;
                    if (adjusted > start
                            && !UnicodeText.isBlank(sequence.index().substring(start, adjusted))) {
                        end = adjusted;
                    }
                } else {
                    int nextEnd = maximumFittingEnd(
                            sequence.index(), end, laterBudget, maxTokens);
                    if (UnicodeText.isBlank(sequence.index().substring(end, nextEnd))) {
                        int seed = sequence.previousNonWhitespace(end - 1);
                        if (seed > start
                                && !UnicodeText.isBlank(sequence.index().substring(start, seed))) {
                            end = seed;
                        }
                    }
                }
            }
            boundaries.add(end);
            start = end;
        }
        List<PlannedDraft> drafts = new ArrayList<>(boundaries.size() - 1);
        for (int index = 0; index + 1 < boundaries.size(); index++) {
            String startReason = index == 0 ? outerStart : BoundaryKind.FORCED_CHARACTER.name();
            String endReason = index + 2 == boundaries.size()
                    ? outerEnd : BoundaryKind.FORCED_CHARACTER.name();
            drafts.add(plannedDraft(sequence.slice(boundaries.get(index), boundaries.get(index + 1)),
                    startReason, endReason, index + 2 < boundaries.size()));
        }
        return drafts;
    }

    private int maximumFittingEnd(UnicodeText.CodePointIndex text, int start,
                                  int budget, int maxTokens) {
        int high = Math.min(text.length(), start + budget);
        if (fits(text.substring(start, high), budget, maxTokens)) return high;
        int low = start;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (fits(text.substring(start, middle), budget, maxTokens)) low = middle;
            else high = middle - 1;
        }
        return low;
    }

    private IllegalArgumentException cannotPackWhitespace() {
        return new IllegalArgumentException(
                "Retained whitespace cannot be packed into useful chunks within the configured limits");
    }

    private String boundaryValue(PlannedDraft draft, String key) {
        Object value = draft.boundaryReason().get(key);
        return value == null ? BoundaryReason.DOCUMENT_END : value.toString();
    }

    private void validateDrafts(List<PlannedDraft> drafts, int firstBudget,
                                int laterBudget, int maxTokens) {
        for (int index = 0; index < drafts.size(); index++) {
            PlannedDraft draft = drafts.get(index);
            int budget = index == 0 ? firstBudget : laterBudget;
            if (draft.content() == null || draft.content().isEmpty()
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

    private MappedFragment mappedFragment(StructuredBlock block,
                                          UnicodeText.CodePointIndex textIndex,
                                          int codePointStart, int codePointEnd) {
        return new MappedFragment(textIndex.substring(codePointStart, codePointEnd), block,
                textIndex, codePointStart, codePointEnd,
                sliceLocator(block, textIndex, codePointStart, codePointEnd));
    }

    private MappedFragment sliceFragment(MappedFragment fragment, int relativeStart, int relativeEnd) {
        if (relativeStart == 0 && relativeEnd == fragment.length()) return fragment;
        if (fragment.block() == null) {
            return MappedFragment.synthetic(UnicodeText.substring(
                    fragment.text(), relativeStart, relativeEnd));
        }
        int sourceStart = fragment.sourceCodePointStart() + relativeStart;
        int sourceEnd = fragment.sourceCodePointStart() + relativeEnd;
        return mappedFragment(fragment.block(), fragment.sourceIndex(), sourceStart, sourceEnd);
    }

    private record PlannedDraft(List<MappedFragment> fragments, String content,
                                SourceLocator sourceLocator, int tokenCount,
                                Map<String, Object> boundaryReason) {
        private ChunkDraft toChunkDraft() {
            return new ChunkDraft(List.of(), content, sourceLocator, tokenCount, boundaryReason);
        }
    }

    private record MappedFragment(String text, StructuredBlock block,
                                  UnicodeText.CodePointIndex sourceIndex,
                                  int sourceCodePointStart, int sourceCodePointEnd,
                                  SourceLocator sourceLocator) {
        private static MappedFragment synthetic(String text) {
            return new MappedFragment(text, null, null, 0, UnicodeText.length(text), null);
        }

        private int length() {
            return sourceCodePointEnd - sourceCodePointStart;
        }
    }

    private final class MappedSequence {
        private final List<MappedFragment> fragments;
        private final String content;
        private final UnicodeText.CodePointIndex index;

        private MappedSequence(List<MappedFragment> fragments) {
            this.fragments = List.copyOf(fragments);
            StringBuilder joined = new StringBuilder();
            fragments.forEach(fragment -> joined.append(fragment.text()));
            this.content = joined.toString();
            this.index = UnicodeText.index(content);
        }

        private String content() { return content; }
        private UnicodeText.CodePointIndex index() { return index; }
        private int length() { return index.length(); }

        private int lastNonWhitespace() {
            for (int offset = content.length(); offset > 0;) {
                int codePoint = content.codePointBefore(offset);
                offset -= Character.charCount(codePoint);
                if (!UnicodeText.isWhitespace(codePoint)) {
                    return content.codePointCount(0, offset);
                }
            }
            return -1;
        }

        private int previousNonWhitespace(int fromCodePoint) {
            for (int offset = Math.min(fromCodePoint, length() - 1); offset >= 0; offset--) {
                int charOffset = index.charIndex(offset);
                if (!UnicodeText.isWhitespace(content.codePointAt(charOffset))) return offset;
            }
            return -1;
        }

        private List<MappedFragment> slice(int codePointStart, int codePointEnd) {
            List<MappedFragment> result = new ArrayList<>();
            int cursor = 0;
            for (MappedFragment fragment : fragments) {
                int fragmentEnd = cursor + fragment.length();
                int intersectionStart = Math.max(cursor, codePointStart);
                int intersectionEnd = Math.min(fragmentEnd, codePointEnd);
                if (intersectionStart < intersectionEnd) {
                    result.add(sliceFragment(fragment,
                            intersectionStart - cursor, intersectionEnd - cursor));
                }
                if (fragmentEnd >= codePointEnd) break;
                cursor = fragmentEnd;
            }
            return List.copyOf(result);
        }
    }

    private final class Accumulator {
        private String content;
        private final List<MappedFragment> fragments = new ArrayList<>();
        private final String startReason;
        private String endReason;

        private Accumulator(MappedFragment fragment, String startReason, String endReason) {
            this.content = fragment.text();
            this.fragments.add(fragment);
            this.startReason = startReason;
            this.endReason = endReason;
        }

        private void append(MappedFragment fragment, String boundaryAfter) {
            content += "\n" + fragment.text();
            fragments.add(MappedFragment.synthetic("\n"));
            fragments.add(fragment);
            endReason = boundaryAfter;
        }

        private PlannedDraft toDraft() {
            return plannedDraft(fragments, startReason, endReason, false);
        }
    }
}
