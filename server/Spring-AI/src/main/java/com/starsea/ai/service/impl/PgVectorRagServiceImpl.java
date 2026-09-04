package com.starsea.ai.service.impl;

import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.auth.AuthErrorCode;
import com.starsea.ai.auth.AuthException;
import com.starsea.ai.chunking.general.UnicodeText;
import com.starsea.ai.chunking.model.ChunkStatus;
import com.starsea.ai.domain.DocumentChunk;
import com.starsea.ai.domain.File;
import com.starsea.ai.mapper.DocumentChunkMapper;
import com.starsea.ai.mapper.ChunkVectorCleanupMapper;
import com.starsea.ai.openapi.retrieval.RetrievalQuery;
import com.starsea.ai.openapi.retrieval.RetrievedChunk;
import com.starsea.ai.service.FileService;
import com.starsea.ai.service.RagService;
import org.springframework.beans.factory.annotation.Autowired;
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
public class PgVectorRagServiceImpl implements RagService {

    static final int MAX_SEARCH_CANDIDATES = 10_000;

    private final VectorStore vectorStore;
    @Lazy
    private final FileService fileService;
    private final DocumentChunkMapper chunkMapper;
    private final ChunkVectorCleanupMapper cleanupMapper;

    public PgVectorRagServiceImpl(VectorStore vectorStore, FileService fileService,
                                  DocumentChunkMapper chunkMapper) {
        this(vectorStore, fileService, chunkMapper, null);
    }

    @Autowired
    public PgVectorRagServiceImpl(VectorStore vectorStore, FileService fileService,
                                  DocumentChunkMapper chunkMapper,
                                  ChunkVectorCleanupMapper cleanupMapper) {
        this.vectorStore = vectorStore;
        this.fileService = fileService;
        this.chunkMapper = chunkMapper;
        this.cleanupMapper = cleanupMapper;
    }


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
        long staleGenerations = cleanupMapper == null ? 0L
                : Math.max(0L, cleanupMapper.countUnprotectedStale(
                tenantId, query.knowledgeIds()));
        int searchLimit = initialSearchLimit(query.topK(), staleGenerations);
        while (true) {
            SearchRequest request = SearchRequest.builder()
                    .query(query.query())
                    .topK(searchLimit)
                    .similarityThreshold(query.scoreThreshold())
                    .filterExpression(filter)
                    .build();
            List<Document> documents = safeDocuments(vectorStore.similaritySearch(request));
            List<RetrievedChunk> current = resolveCurrent(
                    documents, query, tenantId, enabledFiles.keySet());
            if (current.size() >= query.topK() || documents.size() != searchLimit) {
                return current;
            }
            if (searchLimit == MAX_SEARCH_CANDIDATES) {
                throw new IllegalStateException(
                        "Vector candidate limit exhausted before current generations were resolved");
            }
            searchLimit = nextSearchLimit(searchLimit);
        }
    }

    private List<RetrievedChunk> resolveCurrent(List<Document> documents,
                                                 RetrievalQuery query,
                                                 long tenantId,
                                                 Set<Long> enabledFileIds) {
        List<ScoredCandidate> candidates = documents.stream()
                .map(this::candidate)
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
                .filter(chunk -> isPermittedActive(
                        chunk, tenantId, query.knowledgeIds(), enabledFileIds))
                .collect(Collectors.toMap(DocumentChunk::getPublicId, Function.identity(),
                        (first, ignored) -> first, LinkedHashMap::new));
        Set<UUID> emitted = new LinkedHashSet<>();
        return candidates.stream()
                .filter(candidate -> currentGeneration(
                        candidate, activeChunks.get(candidate.publicId())))
                .filter(candidate -> emitted.add(candidate.publicId()))
                .map(candidate -> toRetrievedChunk(candidate, activeChunks.get(candidate.publicId())))
                .filter(Objects::nonNull)
                .limit(query.topK())
                .toList();
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
        if (chunk == null) {
            return null;
        }
        return new RetrievedChunk(chunk.getIndexContent(), candidate.score(), chunk.getSourceFileName(),
                chunk.getSourceDocumentPublicId(), chunk.getPublicId(), chunk.getSourceFileType(),
                mapInteger(chunk.getSourceLocator(), "pageNumber", "page_number"), chunk.getPosition(),
                chunk.getSectionPath(), chunk.getSourceLocator(),
                chunk.getSourceKnowledgePublicId(), chunk.getSourceKnowledgeName());
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
                && !UnicodeText.isBlank(chunk.getContent())
                && chunk.getIndexContent() != null
                && !UnicodeText.isBlank(chunk.getIndexContent())
                && chunk.getSourceDocumentPublicId() != null
                && chunk.getSourceFileName() != null
                && chunk.getSourceFileType() != null;
    }

    private static int initialSearchLimit(int topK, long staleGenerations) {
        if (topK < 1 || topK > MAX_SEARCH_CANDIDATES) {
            throw new IllegalArgumentException(
                    "topK must be between 1 and " + MAX_SEARCH_CANDIDATES);
        }
        long stale = Math.max(0L, staleGenerations);
        if (stale >= MAX_SEARCH_CANDIDATES - (long) topK) {
            return MAX_SEARCH_CANDIDATES;
        }
        return topK + (int) stale;
    }

    private static int nextSearchLimit(int current) {
        return current >= MAX_SEARCH_CANDIDATES / 2
                ? MAX_SEARCH_CANDIDATES : current * 2;
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

    private ScoredCandidate candidate(Document document) {
        UUID vectorId = parseUuid(document.getId());
        if (vectorId == null) {
            return new ScoredCandidate(null, null, normalizeScore(document.getScore()));
        }
        UUID publicId = document.getMetadata().containsKey("documentChunkId")
                ? parseUuid(document.getMetadata().get("documentChunkId")) : vectorId;
        return new ScoredCandidate(publicId, vectorId, normalizeScore(document.getScore()));
    }

    private static boolean currentGeneration(ScoredCandidate candidate, DocumentChunk chunk) {
        if (chunk == null) {
            return false;
        }
        UUID currentVectorId = chunk.getVectorId();
        return currentVectorId == null
                ? Objects.equals(candidate.vectorId(), candidate.publicId())
                : Objects.equals(candidate.vectorId(), currentVectorId);
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

    private record ScoredCandidate(UUID publicId, UUID vectorId, double score) {
    }

}
