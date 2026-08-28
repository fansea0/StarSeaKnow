package com.fansea.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fansea.ai.auth.AuthContext;
import com.fansea.ai.auth.AuthErrorCode;
import com.fansea.ai.auth.AuthException;
import com.fansea.ai.domain.File;
import com.fansea.ai.domain.KnowledgeFile;
import com.fansea.ai.openapi.retrieval.RetrievalQuery;
import com.fansea.ai.openapi.retrieval.RetrievedChunk;
import com.fansea.ai.service.FileService;
import com.fansea.ai.service.KnowledgeFileService;
import com.fansea.ai.service.RagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
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
    public void vectorize(File file, Long knowledgeId) {
        // TODO: 文件分块 ---> 向量化处理 ---> 存入pgvector
        List<Document> documents = handle(file.getPath(), file.getId(), knowledgeId,
                Objects.requireNonNull(file.getPublicId(), "file publicId must not be null"), file.getType());
        this.vectorStore.accept(documents);
    }

    @Override
    public List<RetrievedChunk> retrieve(RetrievalQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        if (query.knowledgeIds().isEmpty()) {
            throw new IllegalArgumentException("knowledgeIds must not be empty");
        }

        String knowledgeIds = query.knowledgeIds().stream()
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining(", "));
        String filter = tenantFilterExpression() + " && knowledgeId in [" + knowledgeIds + "]";
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

        List<Long> fileIds = scoredDocuments.stream()
                .map(result -> metadataLong(result.document(), "fileId"))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (fileIds.isEmpty()) {
            return List.of();
        }

        Map<Long, File> enabledFiles = safeFiles(fileService.listByIds(fileIds)).stream()
                .filter(file -> file != null && file.getId() != null)
                .filter(file -> Integer.valueOf(1).equals(file.getStatus()))
                .filter(file -> file.getPublicId() != null)
                .collect(Collectors.toMap(File::getId, Function.identity(), (first, ignored) -> first,
                        LinkedHashMap::new));

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
        AuthContext ctx = AuthContext.current();
        if (ctx == null || ctx.getTenantId() == null) {
            throw new AuthException(AuthErrorCode.MISSING_TOKEN, "login required");
        }
        return "tenantId == " + ctx.getTenantId();
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


    List<Document> handle(String path, Long fileId, Long knowledgeId, UUID documentPublicId, String fileType) {
        // 解析当前请求的租户ID,所有写入pgvector的Document必须携带tenantId,
        // 否则后续search的 tenantId == N 过滤无法命中任何数据(数据隔离)。
        AuthContext ctx = AuthContext.current();
        if (ctx == null || ctx.getTenantId() == null) {
            throw new AuthException(AuthErrorCode.MISSING_TOKEN, "login required");
        }
        Long tenantId = ctx.getTenantId();

        // 将文件路径path转化为Resource
        Resource resource = new FileSystemResource(path);
        TokenTextSplitter tokenTextSplitter = null;
        if (path.endsWith(".pdf")) {
            PagePdfDocumentReader documentReader = new PagePdfDocumentReader(resource,
                    PdfDocumentReaderConfig.builder()
                            .withPageExtractedTextFormatter(ExtractedTextFormatter.builder()
                                    .withNumberOfBottomTextLinesToDelete(3)
                                    .withNumberOfTopPagesToSkipBeforeDelete(1)
                                    .build())
                            .withPagesPerDocument(1)
                            .build());
            // 对于pdf文件采用大分块方法，适用于一章内容都在一页的PDF
            tokenTextSplitter = new TokenTextSplitter();
            // 兜底注入tenantId/knowledgeId/fileId(PagePdfDocumentReader不会自动设置这些metadata)
            return applyMetadata(tokenTextSplitter.apply(documentReader.get()), tenantId, knowledgeId, fileId,
                    documentPublicId, fileType);
        }else if(path.endsWith(".txt")){
            TextReader documentReader = new TextReader(resource);
            documentReader.getCustomMetadata().put("knowledgeId",knowledgeId);
            documentReader.getCustomMetadata().put("fileId",fileId);
            // 对于txt文件采用小分块方法
            tokenTextSplitter = new TokenTextSplitter(150,100,5,10000,true);
            return applyMetadata(tokenTextSplitter.apply(documentReader.get()), tenantId, knowledgeId, fileId,
                    documentPublicId, fileType);
        }else if (path.endsWith(".md")||path.endsWith(".markdown")){
            MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                    .withHorizontalRuleCreateDocument(true)
                    .withIncludeCodeBlock(false)
                    .withIncludeBlockquote(true)
                    .withAdditionalMetadata("knowledgeId", knowledgeId)
                    .withAdditionalMetadata("fileId", fileId)
                    .build();
            MarkdownDocumentReader reader = new MarkdownDocumentReader(resource, config);
            printDocument(reader.get());
            tokenTextSplitter = new TokenTextSplitter();
            List<Document> documentList = tokenTextSplitter.apply(reader.get());
            printDocument(documentList);
            return applyMetadata(documentList, tenantId, knowledgeId, fileId, documentPublicId, fileType);
        } else if (path.endsWith(".doc")||path.endsWith(".docx")||path.endsWith(".ppt")||path.endsWith(".pptx")||path.endsWith(".html")){
            TikaDocumentReader documentReader = new TikaDocumentReader(resource);
            tokenTextSplitter = new TokenTextSplitter();
            // TikaReader不暴露customMetadata,需要在生成Document后兜底注入
            return applyMetadata(tokenTextSplitter.apply(documentReader.get()), tenantId, knowledgeId, fileId,
                    documentPublicId, fileType);
        }else {
            throw new RuntimeException("不支持的文件类型");
        }
    }

    /**
     * 对 Document 列表统一写入租户、来源与稳定分块 metadata。
     * 用于保证后续 search() 的 tenantId == N 过滤能够命中本租户的所有文件类型向量。
     */
    private static List<Document> applyMetadata(List<Document> documents, Long tenantId, Long knowledgeId, Long fileId,
                                                UUID documentPublicId, String fileType) {
        if (documents == null) return null;
        for (int index = 0; index < documents.size(); index++) {
            Document doc = documents.get(index);
            doc.getMetadata().put("tenantId", tenantId);
            doc.getMetadata().put("knowledgeId", knowledgeId);
            doc.getMetadata().put("fileId", fileId);
            doc.getMetadata().put("documentPublicId", documentPublicId.toString());
            doc.getMetadata().put("chunkId", doc.getId());
            doc.getMetadata().put("chunkIndex", index);
            doc.getMetadata().put("fileType", fileType);
            Object pageNumber = firstMetadata(doc, "pageNumber", "page_number", "start_page_number", "page");
            if (pageNumber != null) {
                doc.getMetadata().put("pageNumber", pageNumber);
            }
        }
        return documents;
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

    private static void printDocument(List<Document> documents) {
        System.out.println("-----------------------------------------------");
        documents.forEach(document -> {
            System.out.println("//"+document.getText()+"//");
        });
        System.out.println("-----------------------------------------------");
    }
}
