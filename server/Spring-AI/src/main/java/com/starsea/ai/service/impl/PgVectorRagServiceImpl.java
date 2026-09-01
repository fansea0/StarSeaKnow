package com.starsea.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.auth.AuthErrorCode;
import com.starsea.ai.auth.AuthException;
import com.starsea.ai.domain.File;
import com.starsea.ai.domain.KnowledgeFile;
import com.starsea.ai.openapi.retrieval.RetrievalQuery;
import com.starsea.ai.openapi.retrieval.RetrievedChunk;
import com.starsea.ai.service.FileService;
import com.starsea.ai.service.KnowledgeFileService;
import com.starsea.ai.service.RagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
@Slf4j
@RequiredArgsConstructor
public class PgVectorRagServiceImpl implements RagService {

    private final VectorStore vectorStore;
    // 不满足迪米特法则
    private final KnowledgeFileService knowledgeFileService;
    @Lazy
    private final FileService fileService;


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
                .topK(query.topK())
                .similarityThreshold(query.scoreThreshold())
                .filterExpression(filter)
                .build();

        List<ScoredDocument> scoredDocuments = safeDocuments(vectorStore.similaritySearch(request)).stream()
                .map(document -> new ScoredDocument(document, normalizeScore(document.getScore())))
                .filter(result -> result.score() >= query.scoreThreshold())
                .toList();
        if (scoredDocuments.isEmpty()) {
            return List.of();
        }

        return scoredDocuments.stream()
                .map(result -> toRetrievedChunk(result, enabledFiles))
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public List<Document> search(String keyword,String knowledgeId) {
        // 基于knowledgeId找到关联的文件排除不可用的向量
        // AND 当前租户过滤(防止跨租户数据泄漏)
        String existing = "knowledgeId == '"+knowledgeId+"'";
        String combined = "(" + existing + ") && " + tenantFilterExpression();
        SearchRequest request = SearchRequest.builder()
                .query(keyword)
                .filterExpression(combined)
                .topK(1)
                .build();
        return vectorStore.similaritySearch(request);
    }

    @Override
    public List<Document> searchByFile(String keyword, Long knowledgeId) {
        List<Long> fileIds = knowledgeFileService.list(new LambdaQueryWrapper<KnowledgeFile>().eq(KnowledgeFile::getKnowledgeId, knowledgeId))
                .stream().map(KnowledgeFile::getFileId)
                .filter(fileId -> fileService.getById(fileId).getStatus() == 1)
                .toList();
        String fileList = String.join(",", fileIds.stream().map(String::valueOf).toList());
        log.info("知识库ID:"+knowledgeId+"对应的文件ID列表:"+fileList);
        // 基于knowledgeId找到关联的文件排除不可用的向量
        String expresString = "knowledgeId == " + knowledgeId + convertToFileInClause(fileIds);
        // AND 当前租户过滤(防止跨租户数据泄漏)
        String combined = "(" + expresString + ") && " + tenantFilterExpression();
        log.info("过滤条件："+expresString+" | 最终:"+combined);
        SearchRequest request = SearchRequest.builder()
                .query(keyword)
                .filterExpression(combined)
                .topK(1)
                .build();
        return vectorStore.similaritySearch(request);
    }

    /**
     * 生成当前请求租户的 pgvector filterExpression(字符串)。
     * 用于在 similaritySearch 与 vectorStore.delete 中 AND 一个 tenantId == N 子句,
     * 防止跨租户数据泄漏。要求调用方处于已登录请求上下文(由 AuthContext 提供)。
     */
    private static String tenantFilterExpression() {
        return tenantFilterExpression(currentTenantId());
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

    private static String convertToFileInClause(List<Long> fileList) {
        int size = fileList.size();
        if (size == 1){
            return "&& fileId == "+fileList.get(0);
        }
        if (fileList == null || fileList.isEmpty()) {
            // -1表示没有文件，就不会查出来任何内容
            return " && fileId == -1";
        }
        String fileListString = fileList.stream()
                .map(Object::toString)
                .collect(Collectors.joining(", "));
        return "&& fileId in [" + fileListString + "]";
    }


    private static RetrievedChunk toRetrievedChunk(ScoredDocument result, Map<Long, File> files) {
        Document document = result.document();
        File file = files.get(metadataLong(document, "fileId"));
        if (file == null) {
            return null;
        }
        UUID chunkId = metadataUuid(document, "chunkId");
        if (chunkId == null) {
            chunkId = parseUuid(document.getId());
        }
        if (chunkId == null) {
            return null;
        }
        return new RetrievedChunk(document.getText(), result.score(), file.getFileName(), file.getPublicId(),
                chunkId, file.getType(), metadataInteger(document, "pageNumber", "page_number"),
                metadataInteger(document, "chunkIndex"));
    }

    private static List<Document> safeDocuments(List<Document> documents) {
        return documents == null ? List.of() : documents;
    }

    private static Collection<File> safeFiles(Collection<File> files) {
        return files == null ? List.of() : files;
    }

    private static double normalizeScore(Double score) {
        if (score == null || !Double.isFinite(score)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, score));
    }

    private static Long metadataLong(Document document, String key) {
        Object value = document.getMetadata().get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String string && string.matches("-?[0-9]+")) {
            try {
                return Long.valueOf(string);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Integer metadataInteger(Document document, String... keys) {
        Object value = firstMetadata(document, keys);
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

    private static UUID metadataUuid(Document document, String key) {
        return parseUuid(document.getMetadata().get(key));
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

    private static Object firstMetadata(Document document, String... keys) {
        for (String key : keys) {
            Object value = document.getMetadata().get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private record ScoredDocument(Document document, double score) {
    }

}
