package com.starsea.ai.service.impl;

import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.auth.AuthErrorCode;
import com.starsea.ai.auth.AuthException;
import com.starsea.ai.chunking.context.ChunkIndexContentBuilder;
import com.starsea.ai.chunking.model.ChunkStatus;
import com.starsea.ai.chunking.model.ChunkType;
import com.starsea.ai.domain.DocumentChunk;
import com.starsea.ai.domain.File;
import com.starsea.ai.mapper.DocumentChunkMapper;
import com.starsea.ai.openapi.retrieval.RetrievalQuery;
import com.starsea.ai.openapi.retrieval.RetrievedChunk;
import com.starsea.ai.service.FileService;
import com.starsea.ai.service.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @Projectname: Spring-AI
 * @Filename: FileServiceImpl
 * @Author: FANSEA
 * @Date:2025/4/26 11:21
 */
@Service
@RequiredArgsConstructor
public class PgVectorRagServiceImpl implements RagService {

    private final VectorStore vectorStore;
    @Lazy
    private final FileService fileService;
    private final DocumentChunkMapper chunkMapper;


    @Override
    public List<RetrievedChunk> retrieve(RetrievalQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        if (query.knowledgeIds().isEmpty()) {
            throw new IllegalArgumentException("knowledgeIds must not be empty");
        }

        Long tenantId = currentTenantId();
        Map<Long, File> enabledFiles = safeFiles(
                fileService.listEnabledByKnowledgeIds(tenantId, query.knowledgeIds())).stream()
                .filter(file -> file != null && file.getId() != null)
                .filter(file -> Integer.valueOf(1).equals(file.getStatus()))
                .filter(file -> file.getPublicId() != null)
                .collect(Collectors.toMap(File::getId, Function.identity(), (first, ignored) -> first,
                        LinkedHashMap::new));
        if (enabledFiles.isEmpty()) {
            return List.of();
        }

        String knowledgeIds = query.knowledgeIds().stream()
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining(", "));
        String fileIds = enabledFiles.keySet().stream()
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining(", "));
        String filter = tenantFilterExpression(tenantId)
                + " && knowledgeId in [" + knowledgeIds + "]"
                + " && fileId in [" + fileIds + "]";
        SearchRequest request = SearchRequest.builder()
                .query(query.query())
                .topK(overfetchTopK(query.topK()))
                .similarityThreshold(query.scoreThreshold())
                .filterExpression(filter)
                .build();

        List<ScoredCandidate> candidates = safeDocuments(vectorStore.similaritySearch(request)).stream()
                .map(document -> new ScoredCandidate(stablePublicId(document),
                        normalizeScore(document.getScore())))
                .filter(candidate -> candidate.publicId() != null)
                .filter(candidate -> candidate.score() >= query.scoreThreshold())
                .toList();
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<UUID> publicIds = new ArrayList<>(new LinkedHashSet<>(candidates.stream()
                .map(ScoredCandidate::publicId)
                .toList()));
        Map<UUID, DocumentChunk> activeChunks = safeChunks(chunkMapper.findActiveByPublicIds(
                tenantId, query.knowledgeIds(), publicIds)).stream()
                .filter(chunk -> isPermittedActive(chunk, tenantId, query.knowledgeIds(), enabledFiles.keySet()))
                .collect(Collectors.toMap(DocumentChunk::getPublicId, Function.identity(),
                        (first, ignored) -> first, LinkedHashMap::new));
        if (activeChunks.isEmpty()) {
            return List.of();
        }

        Set<UUID> emitted = new LinkedHashSet<>();
        List<RetrievedChunk> results = new ArrayList<>();
        for (ScoredCandidate candidate : candidates) {
            DocumentChunk chunk = activeChunks.get(candidate.publicId());
            UUID contextIdentity = contextIdentity(chunk);
            if (contextIdentity == null || !emitted.add(contextIdentity)) {
                continue;
            }
            results.add(toRetrievedChunk(candidate, chunk));
            if (results.size() == query.topK()) {
                break;
            }
        }
        return List.copyOf(results);
    }

    private static String tenantFilterExpression(Long tenantId) {
        return "tenantId == " + tenantId;
    }

    private static Long currentTenantId() {
        AuthContext ctx = AuthContext.current();
        if (ctx == null || ctx.getTenantId() == null) {
            throw new AuthException(AuthErrorCode.MISSING_TOKEN, "login required");
        }
        return ctx.getTenantId();
    }

    private static RetrievedChunk toRetrievedChunk(ScoredCandidate candidate, DocumentChunk chunk) {
        if (isChild(chunk)) {
            return new RetrievedChunk(
                    ChunkIndexContentBuilder.preview(chunk.getParentSectionPath(), chunk.getParentContent()),
                    candidate.score(), chunk.getSourceFileName(), chunk.getSourceDocumentPublicId(),
                    candidate.publicId(), chunk.getSourceFileType(),
                    mapInteger(chunk.getParentSourceLocator(), "pageNumber", "page_number"),
                    chunk.getParentPosition(), chunk.getParentSectionPath(), chunk.getParentSourceLocator(),
                    chunk.getSourceKnowledgePublicId(), chunk.getSourceKnowledgeName());
        }
        return new RetrievedChunk(chunk.getIndexContent(), candidate.score(), chunk.getSourceFileName(),
                chunk.getSourceDocumentPublicId(), candidate.publicId(), chunk.getSourceFileType(),
                mapInteger(chunk.getSourceLocator(), "pageNumber", "page_number"), chunk.getPosition(),
                chunk.getSectionPath(), chunk.getSourceLocator(),
                chunk.getSourceKnowledgePublicId(), chunk.getSourceKnowledgeName());
    }

    private static UUID contextIdentity(DocumentChunk chunk) {
        if (chunk == null) {
            return null;
        }
        return isChild(chunk) ? chunk.getParentPublicId() : chunk.getPublicId();
    }

    private static List<Document> safeDocuments(List<Document> documents) {
        return documents == null ? List.of() : documents;
    }

    private static Collection<File> safeFiles(Collection<File> files) {
        return files == null ? List.of() : files;
    }

    private static Collection<DocumentChunk> safeChunks(Collection<DocumentChunk> chunks) {
        return chunks == null ? List.of() : chunks;
    }

    private static boolean isPermittedActive(DocumentChunk chunk, long tenantId,
                                             Set<Long> knowledgeIds, Set<Long> permittedFileIds) {
        return chunk != null
                && chunk.getPublicId() != null
                && Objects.equals(chunk.getTenantId(), tenantId)
                && knowledgeIds.contains(chunk.getKnowledgeId())
                && permittedFileIds.contains(chunk.getFileId())
                && Integer.valueOf(ChunkStatus.ACTIVE.code()).equals(chunk.getStatus())
                && chunk.getIndexContent() != null
                && !chunk.getIndexContent().isBlank()
                && chunk.getSourceDocumentPublicId() != null
                && chunk.getSourceFileName() != null
                && chunk.getSourceFileType() != null
                && hasValidHierarchyContext(chunk);
    }

    private static boolean hasValidHierarchyContext(DocumentChunk chunk) {
        if (Integer.valueOf(ChunkType.SINGLE.code()).equals(chunk.getChunkType())) {
            return true;
        }
        return isChild(chunk)
                && chunk.getParentChunkId() != null
                && chunk.getParentPublicId() != null
                && chunk.getParentContent() != null
                && !chunk.getParentContent().isBlank()
                && chunk.getParentPosition() != null
                && Integer.valueOf(ChunkStatus.ACTIVE.code()).equals(chunk.getParentStatus());
    }

    private static boolean isChild(DocumentChunk chunk) {
        return chunk != null && Integer.valueOf(ChunkType.CHILD.code()).equals(chunk.getChunkType());
    }

    private static int overfetchTopK(int topK) {
        if (topK < 1) {
            throw new IllegalArgumentException("topK must be positive");
        }
        return (int) Math.min((long) topK * 3L, 100L);
    }

    private static double normalizeScore(Double score) {
        if (score == null || !Double.isFinite(score)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, score));
    }

    private static Integer mapInteger(Map<String, Object> values, String... keys) {
        Object value = firstValue(values, keys);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string && string.matches("-?[0-9]+")) {
            try {
                return Integer.valueOf(string);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static UUID stablePublicId(Document document) {
        UUID documentId = parseUuid(document.getId());
        if (documentId == null) {
            return null;
        }
        if (!document.getMetadata().containsKey("documentChunkId")) {
            return documentId;
        }
        UUID metadataId = parseUuid(document.getMetadata().get("documentChunkId"));
        return documentId.equals(metadataId) ? documentId : null;
    }

    private static UUID parseUuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof String string) {
            try {
                return UUID.fromString(string);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Object firstValue(Map<String, Object> values, String... keys) {
        if (values == null) {
            return null;
        }
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private record ScoredCandidate(UUID publicId, double score) {
    }

}
