package com.starsea.ai.chunking;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.chunking.api.ChunkingApiModels.ChunkResponse;
import com.starsea.ai.chunking.api.ChunkingApiModels.ConfirmRequest;
import com.starsea.ai.chunking.api.ChunkingApiModels.PreviewRequest;
import com.starsea.ai.chunking.context.ChunkIndexContentBuilder;
import com.starsea.ai.chunking.context.DefaultChunkContextEnricher;
import com.starsea.ai.chunking.indexing.ChunkVectorGateway;
import com.starsea.ai.chunking.indexing.ChunkVectorService;
import com.starsea.ai.chunking.indexing.ChunkVectorWorker;
import com.starsea.ai.chunking.markdown.MarkdownParentChildPlanningStrategy;
import com.starsea.ai.chunking.markdown.MarkdownStructureParser;
import com.starsea.ai.chunking.model.ChunkStatus;
import com.starsea.ai.chunking.model.ChunkType;
import com.starsea.ai.chunking.model.PipelineState;
import com.starsea.ai.chunking.preview.ChunkCommandService;
import com.starsea.ai.chunking.preview.ChunkPreviewPersistenceService;
import com.starsea.ai.chunking.preview.ChunkPreviewService;
import com.starsea.ai.chunking.preview.ChunkPreviewWorker;
import com.starsea.ai.chunking.processing.ChunkTaskDispatcher;
import com.starsea.ai.chunking.processing.FileProcessingService;
import com.starsea.ai.chunking.registry.ChunkStrategyRegistry;
import com.starsea.ai.chunking.registry.DocumentStructureParserRegistry;
import com.starsea.ai.chunking.spi.TokenCounter;
import com.starsea.ai.domain.DocumentChunk;
import com.starsea.ai.domain.File;
import com.starsea.ai.domain.FileProcessing;
import com.starsea.ai.mapper.DocumentChunkMapper;
import com.starsea.ai.mapper.FileMapper;
import com.starsea.ai.mapper.FileProcessingMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParentChildChunkingWorkflowTest {

    private static final long TENANT_ID = 1L;
    private static final long KNOWLEDGE_ID = 10L;
    private static final long FILE_ID = 20L;

    @TempDir
    Path tempDir;

    @AfterEach
    void clearAuth() {
        AuthContext.clear();
    }

    @Test
    void registered_parent_child_plan_persists_hierarchy_and_vectors_only_children() throws IOException {
        AuthContext.set(new AuthContext(
                AuthContext.Kind.BUSINESS, 7L, TENANT_ID, "tenant_admin", "jti"));
        WorkflowRepository repository = new WorkflowRepository(writeSource());
        WorkflowServices services = workflowServices(repository);
        Map<String, Object> config = Map.of(
                "parentMode", "PARAGRAPH",
                "parentMaxTokens", 768,
                "childMaxTokens", 256,
                "childOverlapTokens", 32);

        assertTrue(services.preview().strategies(KNOWLEDGE_ID, FILE_ID).strategies().stream()
                .anyMatch(strategy -> strategy.code().equals("PARENT_CHILD")));

        services.preview().startPreview(KNOWLEDGE_ID, FILE_ID,
                new PreviewRequest("PARENT_CHILD", config, false, 0));

        assertEquals(PipelineState.CHUNKED.code(), repository.processing.getPipelineState());
        assertEquals("PARENT_CHILD", repository.processing.getStrategyCode());
        assertEquals("PARAGRAPH", repository.processing.getPolicySnapshot().get("parentMode"));
        assertEquals(768, repository.processing.getPolicySnapshot().get("parentMaxTokens"));
        assertEquals(256, repository.processing.getPolicySnapshot().get("childMaxTokens"));
        assertEquals(32, repository.processing.getPolicySnapshot().get("childOverlapTokens"));
        assertEquals("workflow-word-counter", repository.processing.getPolicySnapshot().get("tokenizer"));

        List<ChunkResponse> listed = services.commands().list(KNOWLEDGE_ID, FILE_ID);
        List<DocumentChunk> parents = repository.chunksOf(ChunkType.PARENT);
        List<DocumentChunk> children = repository.chunksOf(ChunkType.CHILD);
        assertFalse(parents.isEmpty());
        assertTrue(children.size() >= 2, "fixture must make more than one child retrieval unit");
        assertEquals(IntStream.range(0, repository.chunks.size()).boxed().toList(), repository.chunks.stream()
                .map(DocumentChunk::getPosition).toList());
        assertTrue(repository.chunks.stream().filter(chunk -> chunk.getChunkType() == ChunkType.PARENT.code())
                .allMatch(chunk -> chunk.getParentChunkId() == null));
        Map<Long, DocumentChunk> parentsById = parents.stream()
                .collect(java.util.stream.Collectors.toMap(DocumentChunk::getId, parent -> parent));
        assertEquals(IntStream.range(0, parents.size()).boxed().toList(), parents.stream()
                .map(DocumentChunk::getSiblingPosition).toList());
        assertTrue(children.stream().allMatch(chunk -> parentsById.containsKey(chunk.getParentChunkId())
                && parentsById.get(chunk.getParentChunkId()).getPosition() < chunk.getPosition()));
        parents.forEach(parent -> {
            List<DocumentChunk> siblings = children.stream()
                    .filter(child -> parent.getId().equals(child.getParentChunkId())).toList();
            assertFalse(siblings.isEmpty(), "every persisted parent must own at least one CHILD");
            assertEquals(IntStream.range(0, siblings.size()).boxed().toList(), siblings.stream()
                    .map(DocumentChunk::getSiblingPosition).toList());
        });
        assertTrue(parents.stream().noneMatch(chunk -> Boolean.TRUE.equals(chunk.getOverlapEnabled())));
        assertTrue(children.stream().allMatch(chunk -> chunk.getOverlapEnabled()
                && chunk.getOverlapTokenLimit() == 32));
        assertTrue(listed.stream().anyMatch(response -> response.chunkType().equals(ChunkType.PARENT.name())));
        Map<UUID, ChunkResponse> listedChildrenById = listed.stream()
                .filter(response -> response.chunkType().equals(ChunkType.CHILD.name()))
                .collect(java.util.stream.Collectors.toMap(ChunkResponse::publicId, response -> response));
        assertEquals(children.size(), listedChildrenById.size());
        children.forEach(child -> {
            ChunkResponse response = listedChildrenById.get(child.getPublicId());
            assertNotNull(response, "the API list must retain every persisted CHILD");
            assertEquals(child.getPublicId(), response.publicId());
            assertEquals(parentsById.get(child.getParentChunkId()).getPublicId(), response.parentPublicId());
            assertEquals(child.getSiblingPosition(), response.siblingPosition());
        });

        int previewLock = repository.processing.getLockVersion();
        services.vectors().confirm(KNOWLEDGE_ID, FILE_ID, new ConfirmRequest(previewLock));

        assertEquals(PipelineState.COMPLETED.code(), repository.processing.getPipelineState());
        assertEquals(previewLock + 3, repository.processing.getLockVersion(),
                "CHUNKED → CONFIRMED → VECTORIZING → COMPLETED must preserve file lock semantics");
        assertTrue(repository.chunks.stream()
                .allMatch(chunk -> chunk.getStatus() == ChunkStatus.ACTIVE.code()));
        assertEquals(children.stream().map(DocumentChunk::getPublicId).toList(),
                repository.gateway.addedPublicIds());
        assertTrue(repository.gateway.documents().stream()
                .allMatch(document -> document.chunkType() == ChunkType.CHILD
                        && parentsById.get(repository.byPublicId(document.publicId()).getParentChunkId())
                        .getPublicId().equals(document.parentChunkPublicId())));
        assertTrue(repository.chunks.stream().allMatch(chunk -> chunk.getLockVersion() == 2));
    }

    private WorkflowServices workflowServices(WorkflowRepository repository) {
        TokenCounter counter = new WordCounter();
        FileProcessingService states = new FileProcessingService(repository.processingMapper);
        ChunkStrategyRegistry strategies = new ChunkStrategyRegistry(
                List.of(new MarkdownParentChildPlanningStrategy(counter)));
        DocumentStructureParserRegistry parsers = new DocumentStructureParserRegistry(
                List.of(new MarkdownStructureParser(counter)));
        ChunkPreviewWorker previewWorker = new ChunkPreviewWorker(repository.fileMapper,
                repository.processingMapper, parsers, strategies, counter,
                new ChunkPreviewPersistenceService(repository.chunkMapper, repository.processingMapper, states),
                states);
        Executor direct = command -> {
            AuthContext before = AuthContext.current();
            command.run();
            AuthContext.set(before);
        };
        ChunkPreviewService preview = new ChunkPreviewService(repository.processingMapper,
                repository.fileMapper, repository.chunkMapper, strategies, parsers,
                new ChunkTaskDispatcher(states, direct), previewWorker);
        ChunkCommandService commands = new ChunkCommandService(repository.chunkMapper,
                repository.processingMapper, states, counter, new ChunkIndexContentBuilder(), repository.gateway);
        ChunkVectorWorker vectorWorker = new ChunkVectorWorker(repository.processingMapper,
                repository.fileMapper, repository.chunkMapper, states,
                new DefaultChunkContextEnricher(counter), counter, repository.gateway,
                repository.transactions);
        ChunkVectorService vectors = new ChunkVectorService(repository.processingMapper,
                repository.fileMapper, repository.chunkMapper, states, vectorWorker,
                repository.transactions, direct);
        return new WorkflowServices(preview, commands, vectors);
    }

    private Path writeSource() throws IOException {
        Path source = tempDir.resolve("parent-child-workflow.md");
        Files.writeString(source, "# 招生咨询\n\n" + "申请信息 ".repeat(320));
        return source;
    }

    private record WorkflowServices(ChunkPreviewService preview, ChunkCommandService commands,
                                    ChunkVectorService vectors) {
    }

    private static final class WordCounter implements TokenCounter {
        @Override
        public int count(String text) {
            return text == null || text.isBlank() ? 0 : text.trim().split("\\s+").length;
        }

        @Override
        public String id() {
            return "workflow-word-counter";
        }
    }

    private static final class WorkflowRepository {
        private final File file = new File();
        private final FileProcessing processing = new FileProcessing();
        private final List<DocumentChunk> chunks = new ArrayList<>();
        private final RecordingGateway gateway = new RecordingGateway();
        private final FileMapper fileMapper = mock(FileMapper.class);
        private final FileProcessingMapper processingMapper = mock(FileProcessingMapper.class);
        private final DocumentChunkMapper chunkMapper = mock(DocumentChunkMapper.class);
        private final TransactionTemplate transactions = mock(TransactionTemplate.class);
        private long nextChunkId = 1;

        private WorkflowRepository(Path source) {
            Configuration configuration = new Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "parent-child-processing"),
                    FileProcessing.class);
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "parent-child-chunk"),
                    DocumentChunk.class);
            file.setId(FILE_ID);
            file.setPublicId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
            file.setFileName("parent-child-workflow.md");
            file.setType("md");
            file.setPath(source.toString());
            file.setStatus(1);
            processing.setFileId(FILE_ID);
            processing.setTenantId(TENANT_ID);
            processing.setKnowledgeId(KNOWLEDGE_ID);
            processing.setPipelineState(PipelineState.UPLOADED.code());
            processing.setProgress(0);
            processing.setLockVersion(0);
            processing.setPolicySnapshot(Map.of());
            processing.setContextPolicy(Map.of());
            stubTransactions();
            stubProcessing();
            stubChunks();
            when(fileMapper.selectById(FILE_ID)).thenReturn(file);
        }

        private void stubTransactions() {
            when(transactions.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
                TransactionCallback<?> callback = invocation.getArgument(0);
                return callback.doInTransaction(mock(TransactionStatus.class));
            });
            doAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                Consumer<TransactionStatus> callback = invocation.getArgument(0);
                callback.accept(mock(TransactionStatus.class));
                return null;
            }).when(transactions).executeWithoutResult(any());
        }

        private void stubProcessing() {
            when(processingMapper.selectById(FILE_ID)).thenReturn(processing);
            when(processingMapper.findScopedForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                    .thenReturn(processing);
            when(processingMapper.transition(eq(FILE_ID), eq(TENANT_ID), eq(KNOWLEDGE_ID),
                    anyInt(), anyInt(), anyInt(), anyInt(), any(), any()))
                    .thenAnswer(invocation -> {
                        int expected = invocation.getArgument(3);
                        int target = invocation.getArgument(4);
                        int progress = invocation.getArgument(5);
                        int lock = invocation.getArgument(6);
                        if (!Objects.equals(processing.getPipelineState(), expected)
                                || !Objects.equals(processing.getLockVersion(), lock)) return 0;
                        processing.setPipelineState(target);
                        processing.setProgress(progress);
                        processing.setLockVersion(lock + 1);
                        processing.setFailedFromState(invocation.getArgument(7));
                        processing.setLastError(invocation.getArgument(8));
                        return 1;
                    });
            when(processingMapper.update(any(FileProcessing.class), any(Wrapper.class)))
                    .thenAnswer(invocation -> {
                        FileProcessing patch = invocation.getArgument(0);
                        if (patch.getSourceHash() != null) processing.setSourceHash(patch.getSourceHash());
                        if (patch.getStrategyCode() != null) processing.setStrategyCode(patch.getStrategyCode());
                        if (patch.getPlannerVersion() != null) processing.setPlannerVersion(patch.getPlannerVersion());
                        if (patch.getPolicySnapshot() != null) processing.setPolicySnapshot(patch.getPolicySnapshot());
                        return 1;
                    });
        }

        private void stubChunks() {
            when(chunkMapper.findByFile(FILE_ID, TENANT_ID, KNOWLEDGE_ID)).thenAnswer(invocation -> listed());
            when(chunkMapper.findByFileForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                    .thenAnswer(invocation -> ordered());
            when(chunkMapper.deleteReplaceableDrafts(FILE_ID, TENANT_ID, KNOWLEDGE_ID, false))
                    .thenAnswer(invocation -> deleteDrafts());
            when(chunkMapper.insert(any(DocumentChunk.class))).thenAnswer(invocation -> {
                DocumentChunk chunk = invocation.getArgument(0);
                chunk.setId(nextChunkId++);
                chunks.add(chunk);
                return 1;
            });
            when(chunkMapper.update(any(DocumentChunk.class), any(Wrapper.class)))
                    .thenAnswer(invocation -> applyPatch(invocation.getArgument(0), invocation.getArgument(1)));
        }

        private int deleteDrafts() {
            int size = chunks.size();
            chunks.removeIf(chunk -> chunk.getStatus() == ChunkStatus.DRAFT.code());
            return size - chunks.size();
        }

        private int applyPatch(DocumentChunk patch, Wrapper<?> wrapper) {
            DocumentChunk target = chunks.stream().filter(chunk -> matches(wrapper, chunk)).findFirst().orElse(null);
            if (target == null) return 0;
            if (Objects.equals(patch.getStatus(), ChunkStatus.INDEXING.code())) {
                target.setStatus(ChunkStatus.INDEXING.code());
            } else if (Objects.equals(patch.getStatus(), ChunkStatus.ACTIVE.code())) {
                target.setStatus(ChunkStatus.ACTIVE.code());
                target.setIndexContent(patch.getIndexContent());
                target.setOverlapContent(patch.getOverlapContent());
                target.setOverlapSourceChunkId(patch.getOverlapSourceChunkId());
                target.setOverlapTokenCount(patch.getOverlapTokenCount());
            } else {
                return 0;
            }
            target.setLockVersion(target.getLockVersion() + 1);
            return 1;
        }

        private boolean matches(Wrapper<?> wrapper, DocumentChunk chunk) {
            if (!(wrapper instanceof AbstractWrapper<?, ?, ?> update)) return false;
            return hasCondition(update, "id", chunk.getId())
                    && hasCondition(update, "public_id", chunk.getPublicId())
                    && hasCondition(update, "status", chunk.getStatus())
                    && hasCondition(update, "lock_version", chunk.getLockVersion());
        }

        private boolean hasCondition(AbstractWrapper<?, ?, ?> wrapper, String column, Object value) {
            Pattern pattern = Pattern.compile("(?i)(?:^|[^a-z0-9_])" + Pattern.quote(column)
                    + "\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)}");
            Matcher matcher = pattern.matcher(wrapper.getSqlSegment());
            return matcher.find() && Objects.equals(
                    wrapper.getParamNameValuePairs().get(matcher.group(1)), value);
        }

        private List<DocumentChunk> ordered() {
            return chunks.stream().sorted(Comparator.comparing(DocumentChunk::getPosition)).toList();
        }

        private List<DocumentChunk> listed() {
            Map<Long, UUID> parentPublicIds = chunks.stream()
                    .filter(chunk -> chunk.getChunkType() == ChunkType.PARENT.code())
                    .collect(java.util.stream.Collectors.toMap(DocumentChunk::getId, DocumentChunk::getPublicId));
            chunks.stream().filter(chunk -> chunk.getChunkType() == ChunkType.CHILD.code())
                    .forEach(chunk -> chunk.setParentPublicId(parentPublicIds.get(chunk.getParentChunkId())));
            return ordered();
        }

        private List<DocumentChunk> chunksOf(ChunkType type) {
            return chunks.stream().filter(chunk -> chunk.getChunkType() == type.code()).toList();
        }

        private DocumentChunk byPublicId(UUID publicId) {
            return chunks.stream().filter(chunk -> chunk.getPublicId().equals(publicId)).findFirst().orElseThrow();
        }
    }

    private static final class RecordingGateway implements ChunkVectorGateway {
        private final List<VectorDocument> documents = new ArrayList<>();

        @Override
        public void delete(UUID publicId) {
            documents.removeIf(document -> document.publicId().equals(publicId));
        }

        @Override
        public void add(List<VectorDocument> added) {
            documents.addAll(added);
        }

        private List<UUID> addedPublicIds() {
            return documents.stream().map(VectorDocument::publicId).toList();
        }

        private List<VectorDocument> documents() {
            return List.copyOf(documents);
        }
    }
}
