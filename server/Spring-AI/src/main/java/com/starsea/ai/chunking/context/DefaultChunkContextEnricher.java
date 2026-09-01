package com.starsea.ai.chunking.context;

import com.starsea.ai.chunking.model.ContextPolicy;
import com.starsea.ai.chunking.model.EnrichedChunk;
import com.starsea.ai.chunking.spi.ChunkContextEnricher;
import com.starsea.ai.chunking.spi.TokenCounter;
import com.starsea.ai.domain.DocumentChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Generic post-persistence context enhancer; it never parses or persists source Markdown. */
@Component
public final class DefaultChunkContextEnricher implements ChunkContextEnricher {

    private static final int GLOBAL_MAX_TOKENS = 512;

    private final TokenCounter tokenCounter;
    private final ChunkIndexContentBuilder contentBuilder;
    private final SentenceBoundaryDetector sentenceBoundaryDetector;

    public DefaultChunkContextEnricher(TokenCounter tokenCounter) {
        this(tokenCounter, new ChunkIndexContentBuilder(), new SentenceBoundaryDetector());
    }

    DefaultChunkContextEnricher(TokenCounter tokenCounter, ChunkIndexContentBuilder contentBuilder,
                                SentenceBoundaryDetector sentenceBoundaryDetector) {
        this.tokenCounter = Objects.requireNonNull(tokenCounter, "tokenCounter");
        this.contentBuilder = Objects.requireNonNull(contentBuilder, "contentBuilder");
        this.sentenceBoundaryDetector = Objects.requireNonNull(sentenceBoundaryDetector, "sentenceBoundaryDetector");
    }

    @Override
    public List<EnrichedChunk> enrich(List<DocumentChunk> chunks, ContextPolicy policy, int maxTokens) {
        Objects.requireNonNull(chunks, "chunks");
        Objects.requireNonNull(policy, "policy");
        if (maxTokens < 1 || maxTokens > GLOBAL_MAX_TOKENS) {
            throw new IllegalArgumentException("maxTokens must be between 1 and 512");
        }

        List<DocumentChunk> ordered = chunks.stream().filter(Objects::nonNull)
                .sorted(Comparator.comparing(DocumentChunk::getPosition, Comparator.nullsLast(Integer::compareTo)))
                .toList();
        List<EnrichedChunk> enriched = new ArrayList<>(ordered.size());
        DocumentChunk previous = null;
        for (DocumentChunk current : ordered) {
            String body = current.getContent() == null ? "" : current.getContent();
            String withoutOverlap = contentBuilder.build(current.getSectionPath(), null, body);
            int baseTokens = tokenCounter.count(withoutOverlap);
            if (baseTokens > maxTokens) {
                throw new IllegalArgumentException("Current index text exceeds maxTokens");
            }

            String overlap = null;
            int overlapTokens = 0;
            Long sourceId = null;
            if (policy.enabled() && previous != null && canContinue(previous, current) && baseTokens < maxTokens) {
                overlap = boundedOverlap(previous.getContent(), current.getSectionPath(), body,
                        policy.overlapTokens(), maxTokens);
                if (overlap != null) {
                    String withOverlap = contentBuilder.build(current.getSectionPath(), overlap, body);
                    overlapTokens = tokenCounter.count(withOverlap) - baseTokens;
                    sourceId = previous.getId();
                }
            }
            String indexContent = contentBuilder.build(current.getSectionPath(), overlap, body);
            enriched.add(new EnrichedChunk(current, sourceId, overlap, overlapTokens, indexContent));
            previous = current;
        }
        return List.copyOf(enriched);
    }

    private String boundedOverlap(String previousBody, List<String> path, String currentBody,
                                  int overlapBudget, int maxTokens) {
        if (overlapBudget == 0 || previousBody == null || previousBody.isBlank()) {
            return null;
        }
        List<SentenceBoundaryDetector.Sentence> sentences = sentenceBoundaryDetector.completeSentences(previousBody);
        if (sentences.isEmpty()) {
            return null;
        }
        String selected = null;
        int baseTokens = tokenCounter.count(contentBuilder.build(path, null, currentBody));
        for (int index = sentences.size() - 1; index >= 0; index--) {
            String candidate = previousBody.substring(sentences.get(index).startOffset(),
                    sentences.get(sentences.size() - 1).endOffset()).strip();
            int overlapTokens = tokenCounter.count(contentBuilder.build(path, candidate, currentBody)) - baseTokens;
            if (overlapTokens > overlapBudget || baseTokens + overlapTokens > maxTokens) {
                break;
            }
            selected = candidate;
        }
        return selected;
    }

    private boolean canContinue(DocumentChunk previous, DocumentChunk current) {
        return previous.getPosition() != null
                && current.getPosition() != null
                && current.getPosition() == previous.getPosition() + 1
                && Objects.equals(previous.getSectionPath(), current.getSectionPath())
                && !isStructuralBoundary(previous.getBoundaryReason(), "end")
                && !isStructuralBoundary(current.getBoundaryReason(), "start");
    }

    private boolean isStructuralBoundary(Map<String, Object> boundaryReason, String key) {
        if (boundaryReason == null) {
            return false;
        }
        String reason = String.valueOf(boundaryReason.getOrDefault(key, "PARAGRAPH_END"));
        return reason.equals("THEMATIC_BREAK") || reason.equals("PEER_LABEL") || reason.equals("CONTAINER_END")
                || reason.matches("H[1-6]_SECTION");
    }
}
