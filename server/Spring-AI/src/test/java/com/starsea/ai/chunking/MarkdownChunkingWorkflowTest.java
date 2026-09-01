package com.starsea.ai.chunking;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.chunking.context.DefaultChunkContextEnricher;
import com.starsea.ai.chunking.indexing.ChunkVectorGateway;
import com.starsea.ai.chunking.indexing.ChunkVectorWorker;
import com.starsea.ai.chunking.indexing.SpringAiChunkVectorGateway;
import com.starsea.ai.chunking.markdown.MarkdownChunkPlanningStrategy;
import com.starsea.ai.chunking.markdown.MarkdownStructureParser;
import com.starsea.ai.chunking.model.ChunkDraft;
import com.starsea.ai.chunking.model.ChunkPolicy;
import com.starsea.ai.chunking.model.ChunkStatus;
import com.starsea.ai.chunking.model.ContextPolicy;
import com.starsea.ai.chunking.model.FileResource;
import com.starsea.ai.chunking.model.PipelineState;
import com.starsea.ai.chunking.model.SourceLocator;
import com.starsea.ai.chunking.processing.FileProcessingService;
import com.starsea.ai.chunking.spi.TokenCounter;
import com.starsea.ai.chunking.token.HuggingFaceTokenCounter;
import com.starsea.ai.domain.DocumentChunk;
import com.starsea.ai.domain.File;
import com.starsea.ai.domain.FileProcessing;
import com.starsea.ai.mapper.DocumentChunkMapper;
import com.starsea.ai.mapper.FileMapper;
import com.starsea.ai.mapper.FileProcessingMapper;
import com.starsea.ai.openapi.retrieval.RetrievalQuery;
import com.starsea.ai.openapi.retrieval.RetrievedChunk;
import com.starsea.ai.service.FileService;
import com.starsea.ai.service.impl.PgVectorRagServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarkdownChunkingWorkflowTest {

    private static final long TENANT_ID = 1L;
    private static final long KNOWLEDGE_ID = 10L;
    private static final long FILE_ID = 20L;
    private static final UUID FILE_PUBLIC_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @AfterEach
    void clearAuth() {
        AuthContext.clear();
    }

    @Test
    void uploaded_markdown_can_be_adjusted_indexed_with_overlap_and_retrieved_from_saved_chunks()
            throws IOException {
        AuthContext.set(new AuthContext(
                AuthContext.Kind.BUSINESS, 7L, TENANT_ID, "tenant_admin", "jti"));
        Path uploadedSource = Path.of("src/main/resources/file/科大百事通.md")
                .toAbsolutePath().normalize();

        try (ExactCounter exact = exactCounter()) {
            TokenCounter counter = exact.counter();
            FileResource uploadedFile = new FileResource(
                    TENANT_ID, KNOWLEDGE_ID, FILE_ID, FILE_PUBLIC_ID,
                    "科大百事通.md", "md", uploadedSource);
            List<ChunkDraft> preview = new MarkdownChunkPlanningStrategy(counter).plan(
                    new MarkdownStructureParser(counter).parse(uploadedFile),
                    ChunkPolicy.defaults());

            assertTrue(preview.size() > 1, "the preview must permit a delete operation");
            List<DocumentChunk> savedChunks = persistDrafts(preview);
            DocumentChunk edited = savedChunks.stream()
                    .filter(chunk -> chunk.getContent().contains("Q1"))
                    .findFirst()
                    .orElseThrow();
            edited.setContent(edited.getContent() + "\n\n自主招生咨询专线已经开通。");
            edited.setTokenCount(counter.count(indexText(edited)));
            edited.setIsModified(true);
            edited.setLockVersion(edited.getLockVersion() + 1);

            DocumentChunk deleted = savedChunks.stream()
                    .filter(chunk -> chunk != edited)
                    .findFirst()
                    .orElseThrow();
            savedChunks.remove(deleted);
            for (int position = 0; position < savedChunks.size(); position++) {
                savedChunks.get(position).setPosition(position);
            }

            ContextPolicy confirmedPolicy = new ContextPolicy(true, 40);
            FakeVectorStore vectorStore = new FakeVectorStore();
            ChunkVectorGateway gateway = new SpringAiChunkVectorGateway(vectorStore, new ObjectMapper());
            vectorizeConfirmedChunks(savedChunks, uploadedFile, confirmedPolicy, counter, gateway);

            assertTrue(savedChunks.stream()
                    .allMatch(chunk -> chunk.getStatus() == ChunkStatus.ACTIVE.code()));
            assertFalse(savedChunks.stream()
                    .anyMatch(chunk -> chunk.getPublicId().equals(deleted.getPublicId())));
            assertTrue(edited.getIndexContent().contains("自主招生咨询专线已经开通。"));

            RetrievedChunk result = retrieve(vectorStore, savedChunks, "自主招生咨询专线");
            assertEquals(edited.getPublicId(), result.chunkId());
            assertEquals(edited.getIndexContent(), result.content(),
                    "retrieval must use the saved index_content, not vector-store text");
            assertEquals(edited.getSectionPath(), result.sectionPath());
            assertEquals(edited.getSourceLocator(), result.sourceLocator());
            assertNotNull(result.sourceLocator().get("startLine"));
            assertNotNull(result.sourceLocator().get("endLine"));
            assertTrue(((Number) result.sourceLocator().get("startLine")).intValue() >= 1);
            assertTrue(((Number) result.sourceLocator().get("endLine")).intValue()
                    <= java.nio.file.Files.readAllLines(uploadedSource).size());
        }
    }

    private List<DocumentChunk> persistDrafts(List<ChunkDraft> drafts) {
        List<DocumentChunk> chunks = new ArrayList<>();
        for (int position = 0; position < drafts.size(); position++) {
            ChunkDraft draft = drafts.get(position);
            DocumentChunk chunk = new DocumentChunk();
            chunk.setId((long) position + 1);
            chunk.setPublicId(UUID.nameUUIDFromBytes(
                    ("科大百事通-" + position).getBytes(StandardCharsets.UTF_8)));
            chunk.setTenantId(TENANT_ID);
            chunk.setKnowledgeId(KNOWLEDGE_ID);
            chunk.setFileId(FILE_ID);
            chunk.setPosition(position);
            chunk.setContent(draft.content());
            chunk.setSectionPath(draft.sectionPath());
            chunk.setSourceLocator(sourceMap(draft.sourceLocator()));
            chunk.setTokenCount(draft.tokenCount());
            chunk.setBoundaryReason(draft.boundaryReason());
            chunk.setStatus(ChunkStatus.DRAFT.code());
            chunk.setIsModified(false);
            chunk.setLockVersion(0);
            chunks.add(chunk);
        }
        return chunks;
    }

    private Map<String, Object> sourceMap(SourceLocator source) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("type", source.type());
        values.put("blockIds", source.blockIds());
        values.put("startOffset", source.startOffset());
        values.put("endOffset", source.endOffset());
        values.put("startLine", source.startLine());
        values.put("endLine", source.endLine());
        return values;
    }

    private String indexText(DocumentChunk chunk) {
        return chunk.getSectionPath().isEmpty()
                ? chunk.getContent()
                : "标题：" + String.join(" > ", chunk.getSectionPath()) + "\n\n" + chunk.getContent();
    }

    private void vectorizeConfirmedChunks(List<DocumentChunk> chunks,
                                          FileResource uploadedFile,
                                          ContextPolicy policy,
                                          TokenCounter counter,
                                          ChunkVectorGateway gateway) {
        chunks.forEach(chunk -> {
            chunk.setStatus(ChunkStatus.INDEXING.code());
            chunk.setLockVersion(chunk.getLockVersion() + 1);
        });
        FileProcessing processing = new FileProcessing();
        processing.setFileId(FILE_ID);
        processing.setTenantId(TENANT_ID);
        processing.setKnowledgeId(KNOWLEDGE_ID);
        processing.setPipelineState(PipelineState.VECTORIZING.code());
        processing.setLockVersion(5);
        processing.setSourceHash("sample-hash");
        processing.setPolicySnapshot(Map.of("maxTokens", 512));
        processing.setContextPolicy(Map.of("overlapEnabled", true, "overlapTokens", 40));

        File file = new File();
        file.setId(FILE_ID);
        file.setPublicId(FILE_PUBLIC_ID);
        file.setFileName(uploadedFile.fileName());
        file.setType(uploadedFile.fileType());
        file.setPath(uploadedFile.path().toString());
        file.setStatus(1);

        FileProcessingMapper processingMapper = mock(FileProcessingMapper.class);
        FileMapper fileMapper = mock(FileMapper.class);
        DocumentChunkMapper chunkMapper = mock(DocumentChunkMapper.class);
        FileProcessingService stateService = mock(FileProcessingService.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(processingMapper.findScopedForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                .thenReturn(processing);
        when(fileMapper.selectById(FILE_ID)).thenReturn(file);
        when(chunkMapper.findByFileForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                .thenReturn(chunks);
        AtomicInteger activationIndex = new AtomicInteger();
        when(chunkMapper.update(any(DocumentChunk.class), any())).thenAnswer(invocation -> {
            DocumentChunk patch = invocation.getArgument(0);
            if (Integer.valueOf(ChunkStatus.ACTIVE.code()).equals(patch.getStatus())) {
                DocumentChunk target = chunks.get(activationIndex.getAndIncrement());
                target.setStatus(patch.getStatus());
                target.setOverlapContent(patch.getOverlapContent());
                target.setOverlapSourceChunkId(patch.getOverlapSourceChunkId());
                target.setOverlapTokenCount(patch.getOverlapTokenCount());
                target.setIndexContent(patch.getIndexContent());
                target.setLockVersion(target.getLockVersion() + 1);
            }
            return 1;
        });
        when(stateService.transition(KNOWLEDGE_ID, FILE_ID, PipelineState.VECTORIZING,
                PipelineState.COMPLETED, 5)).thenReturn(new FileProcessingService.Transition(
                KNOWLEDGE_ID, FILE_ID, PipelineState.VECTORIZING,
                PipelineState.COMPLETED, 6, 100, null, null));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());

        ChunkVectorWorker worker = new ChunkVectorWorker(
                processingMapper, fileMapper, chunkMapper, stateService,
                new DefaultChunkContextEnricher(counter), counter, gateway, transactions);
        List<ChunkVectorWorker.ChunkSnapshot> snapshots = chunks.stream()
                .map(ChunkVectorWorker.ChunkSnapshot::fromIndexing)
                .toList();
        worker.vectorizeBatch(new ChunkVectorWorker.BatchJob(
                TENANT_ID, KNOWLEDGE_ID, FILE_ID, 5, "sample-hash", policy, 512,
                ChunkVectorWorker.FileSnapshot.from(file), snapshots, snapshots));

        chunks.forEach(chunk -> {
            chunk.setSourceDocumentPublicId(FILE_PUBLIC_ID);
            chunk.setSourceFileName(uploadedFile.fileName());
            chunk.setSourceFileType(uploadedFile.fileType());
        });
    }

    private RetrievedChunk retrieve(FakeVectorStore vectorStore,
                                    List<DocumentChunk> savedChunks,
                                    String queryText) {
        File file = new File();
        file.setId(FILE_ID);
        file.setPublicId(FILE_PUBLIC_ID);
        file.setFileName("科大百事通.md");
        file.setType("md");
        file.setStatus(1);
        FileService fileService = mock(FileService.class);
        when(fileService.listEnabledByKnowledgeIds(TENANT_ID, Set.of(KNOWLEDGE_ID)))
                .thenReturn(List.of(file));
        DocumentChunkMapper chunkMapper = mock(DocumentChunkMapper.class);
        when(chunkMapper.findActiveByPublicIds(eq(TENANT_ID), eq(Set.of(KNOWLEDGE_ID)), any()))
                .thenReturn(savedChunks);
        PgVectorRagServiceImpl retrieval = new PgVectorRagServiceImpl(
                vectorStore, fileService, chunkMapper);

        List<RetrievedChunk> results = retrieval.retrieve(
                new RetrievalQuery(queryText, Set.of(KNOWLEDGE_ID), 1, 0.0));
        assertEquals(1, results.size());
        return results.get(0);
    }

    private ExactCounter exactCounter() throws IOException {
        HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.newInstance(
                new ClassPathResource("tokenizer/bge-base-zh-v1.5-tokenizer.json")
                        .getInputStream(), Map.of());
        return new ExactCounter(new HuggingFaceTokenCounter(
                tokenizer, "BAAI/bge-base-zh-v1.5@7dfbf196"));
    }

    private record ExactCounter(HuggingFaceTokenCounter counter) implements AutoCloseable {
        @Override
        public void close() {
            counter.close();
        }
    }

    private static final class FakeVectorStore implements VectorStore {
        private final Map<String, Document> documents = new LinkedHashMap<>();

        @Override
        public void add(List<Document> values) {
            values.forEach(value -> documents.put(value.getId(), value));
        }

        @Override
        public void delete(List<String> ids) {
            ids.forEach(documents::remove);
        }

        @Override
        public void delete(Filter.Expression filterExpression) {
            documents.clear();
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            return documents.values().stream()
                    .filter(document -> document.getText().contains(request.getQuery()))
                    .limit(request.getTopK())
                    .map(document -> document.mutate()
                            .text("stale vector payload")
                            .score(0.97)
                            .build())
                    .toList();
        }
    }
}
