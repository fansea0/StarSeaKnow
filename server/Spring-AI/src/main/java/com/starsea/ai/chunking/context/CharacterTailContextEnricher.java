package com.starsea.ai.chunking.context;

import com.starsea.ai.chunking.model.EnrichedChunk;
import com.starsea.ai.chunking.model.GeneralChunkConfig;
import com.starsea.ai.chunking.model.OverlapUnit;
import com.starsea.ai.chunking.runtime.ChunkRuntimePolicy;
import com.starsea.ai.chunking.spi.TokenCounter;
import com.starsea.ai.domain.DocumentChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Adds a bounded Unicode code-point tail for GENERAL chunks. */
public final class CharacterTailContextEnricher {

    private final TokenCounter tokenCounter;
    private final ChunkIndexContentBuilder contentBuilder;

    public CharacterTailContextEnricher(TokenCounter tokenCounter) {
        this(tokenCounter, new ChunkIndexContentBuilder());
    }

    CharacterTailContextEnricher(TokenCounter tokenCounter, ChunkIndexContentBuilder contentBuilder) {
        this.tokenCounter = Objects.requireNonNull(tokenCounter, "tokenCounter");
        this.contentBuilder = Objects.requireNonNull(contentBuilder, "contentBuilder");
    }

    public List<EnrichedChunk> enrich(List<DocumentChunk> chunks, ChunkRuntimePolicy policy) {
        Objects.requireNonNull(chunks, "chunks");
        Objects.requireNonNull(policy, "policy");
        if (!(policy.strategyConfig() instanceof GeneralChunkConfig config)) {
            throw new IllegalArgumentException("Character-tail context requires GENERAL policy");
        }
        List<DocumentChunk> ordered = chunks.stream().filter(Objects::nonNull)
                .sorted(Comparator.comparing(DocumentChunk::getPosition,
                        Comparator.nullsLast(Integer::compareTo)))
                .toList();
        List<EnrichedChunk> result = new ArrayList<>(ordered.size());
        DocumentChunk previous = null;
        for (DocumentChunk current : ordered) {
            result.add(enrichOne(previous, current, config.maxCharacters(), policy.maxIndexTokens()));
            previous = current;
        }
        return List.copyOf(result);
    }

    private EnrichedChunk enrichOne(DocumentChunk previous, DocumentChunk current,
                                    int maxCharacters, int maxTokens) {
        String body = current.getContent() == null ? "" : current.getContent();
        String base = contentBuilder.build(current.getSectionPath(), null, body);
        int baseCharacters = codePoints(base);
        int baseTokens = tokenCounter.count(base);
        if (baseCharacters > maxCharacters || baseTokens > maxTokens) {
            throw new IllegalArgumentException("Current index text exceeds GENERAL policy limits");
        }

        boolean enabled = Boolean.TRUE.equals(current.getOverlapEnabled());
        int limit = current.getOverlapLimit() == null ? 0 : current.getOverlapLimit();
        if (current.getOverlapUnit() != OverlapUnit.CHARACTERS) {
            throw new IllegalArgumentException("GENERAL chunk overlap unit must be CHARACTERS");
        }
        if (!enabled || limit <= 0) {
            return empty(current, base, OverlapReductionReason.DISABLED);
        }
        if (previous == null) {
            return empty(current, base, OverlapReductionReason.FIRST_CHUNK);
        }
        if (!adjacent(previous, current)) {
            return empty(current, base, OverlapReductionReason.NO_ADJACENT_SOURCE);
        }
        String source = previous.getContent() == null ? "" : previous.getContent();
        int sourceLength = codePoints(source);
        if (sourceLength == 0) {
            return empty(current, base, OverlapReductionReason.SOURCE_EMPTY);
        }

        int configuredLength = Math.min(limit, sourceLength);
        String configuredTail = tail(source, configuredLength);
        String configuredIndex = contentBuilder.build(current.getSectionPath(), configuredTail, body);
        boolean characterReduced = codePoints(configuredIndex) > maxCharacters;
        boolean tokenReduced = tokenCounter.count(configuredIndex) > maxTokens;
        for (int length = configuredLength; length > 0; length--) {
            String candidate = tail(source, length);
            String index = contentBuilder.build(current.getSectionPath(), candidate, body);
            if (codePoints(index) <= maxCharacters && tokenCounter.count(index) <= maxTokens) {
                OverlapReductionReason reason;
                if (length == configuredLength) {
                    reason = sourceLength > limit
                            ? OverlapReductionReason.CONFIGURED_LIMIT : OverlapReductionReason.NONE;
                } else if (tokenReduced && !characterReduced) {
                    reason = OverlapReductionReason.MODEL_TOKEN_LIMIT;
                } else {
                    reason = OverlapReductionReason.CHARACTER_LIMIT;
                }
                return enriched(current, previous.getId(), candidate, baseTokens, index, reason);
            }
        }
        return empty(current, base, OverlapReductionReason.FORMAT_OVERHEAD);
    }

    private EnrichedChunk enriched(DocumentChunk current, Long sourceId, String overlap,
                                   int baseTokens, String index, OverlapReductionReason reason) {
        return new EnrichedChunk(current, sourceId, overlap,
                tokenCounter.count(index) - baseTokens, codePoints(overlap), reason.name(), index);
    }

    private EnrichedChunk empty(DocumentChunk current, String index, OverlapReductionReason reason) {
        return new EnrichedChunk(current, null, null, 0, 0, reason.name(), index);
    }

    private boolean adjacent(DocumentChunk previous, DocumentChunk current) {
        return previous.getPosition() != null && current.getPosition() != null
                && current.getPosition() == previous.getPosition() + 1;
    }

    private int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }

    private String tail(String value, int codePoints) {
        int start = value.offsetByCodePoints(value.length(), -codePoints);
        return value.substring(start);
    }
}
