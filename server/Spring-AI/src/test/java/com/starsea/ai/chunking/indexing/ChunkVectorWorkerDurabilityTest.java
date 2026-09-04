package com.starsea.ai.chunking.indexing;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.starsea.ai.chunking.api.ChunkingException;
import com.starsea.ai.chunking.model.ChunkStatus;
import com.starsea.ai.chunking.model.EnrichedChunk;
import com.starsea.ai.chunking.model.PipelineState;
import com.starsea.ai.chunking.processing.FileProcessingService;
import com.starsea.ai.chunking.runtime.ChunkRuntimePolicyResolver;
import com.starsea.ai.chunking.spi.ChunkContextEnricher;
import com.starsea.ai.chunking.spi.TokenCounter;
import com.starsea.ai.domain.DocumentChunk;
import com.starsea.ai.domain.File;
import com.starsea.ai.domain.FileProcessing;
import com.starsea.ai.mapper.DocumentChunkMapper;
import com.starsea.ai.mapper.FileMapper;
import com.starsea.ai.mapper.FileProcessingMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChunkVectorWorkerDurabilityTest {

    private static final long TENANT_ID = 1L;
    private static final long KNOWLEDGE_ID = 10L;
    private static final long FILE_ID = 20L;
    private static final UUID CHUNK_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID FILE_PUBLIC_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OLD_VECTOR_ID =
            UUID.fromString("77777777-7777-7777-7777-777777777777");

    @Test
    void uncertain_activation_commit_never_deletes_the_generation_found_active_in_sql_state() {
        Fixture fixture = fixture(true, false);

        fixture.worker.vectorizeBatch(fixture.job);

        assertEquals(PipelineState.COMPLETED.code(), fixture.processing.getPipelineState());
        assertEquals(ChunkStatus.ACTIVE.code(), fixture.chunk.getStatus());
        assertEquals(fixture.job.vectorId(fixture.chunk.getId()), fixture.chunk.getVectorId());
        assertEquals(Map.of(fixture.chunk.getVectorId(), "new index"), fixture.gateway.values);
        assertEquals(Set.of(), fixture.lifecycle.queued);
    }

    @Test
    void hard_crash_after_add_leaves_pending_generation_for_recovery_to_drain() {
        Fixture fixture = fixture(false, true);
        UUID pending = fixture.job.vectorId(fixture.chunk.getId());

        assertThrows(SimulatedProcessDeath.class,
                () -> fixture.worker.vectorizeBatch(fixture.job));

        assertEquals(PipelineState.VECTORIZING.code(), fixture.processing.getPipelineState());
        assertEquals(ChunkStatus.INDEXING.code(), fixture.chunk.getStatus());
        assertEquals(pending, fixture.chunk.getPendingVectorId());
        assertEquals("new index", fixture.gateway.values.get(pending));
        assertEquals(Set.of(), fixture.lifecycle.queued);

        fixture.lifecycle.enqueuePendingOwner(TENANT_ID, KNOWLEDGE_ID, FILE_ID, 5);
        fixture.chunk.setStatus(ChunkStatus.DRAFT.code());
        fixture.chunk.setPendingVectorId(null);
        fixture.chunk.setIndexingLockVersion(null);
        fixture.processing.setPipelineState(PipelineState.FAILED.code());
        fixture.lifecycle.drain();

        assertEquals(Map.of(OLD_VECTOR_ID, "old index"), fixture.gateway.values);
        assertEquals(Set.of(), fixture.lifecycle.queued);
    }

    @Test
    void whitespace_body_activates_its_reserved_identity_without_embedding_and_cleans_old_vector() {
        Fixture fixture = fixture(false, false, " \u3000\n", " \u3000\n");
        UUID reserved = fixture.job.vectorId(fixture.chunk.getId());

        fixture.worker.vectorizeBatch(fixture.job);

        assertEquals(PipelineState.COMPLETED.code(), fixture.processing.getPipelineState());
        assertEquals(ChunkStatus.ACTIVE.code(), fixture.chunk.getStatus());
        assertEquals(reserved, fixture.chunk.getVectorId());
        assertEquals(" \u3000\n", fixture.chunk.getIndexContent());
        assertEquals(Map.of(), fixture.gateway.values);
        assertEquals(0, fixture.gateway.addInvocations.get());
        assertEquals(Set.of(), fixture.lifecycle.queued);
    }

    private Fixture fixture(boolean uncertainCommit, boolean crashAfterAdd) {
        return fixture(uncertainCommit, crashAfterAdd, "body", "new index");
    }

    private Fixture fixture(boolean uncertainCommit, boolean crashAfterAdd,
                            String body, String indexContent) {
        FileProcessing processing = new FileProcessing();
        processing.setFileId(FILE_ID);
        processing.setTenantId(TENANT_ID);
        processing.setKnowledgeId(KNOWLEDGE_ID);
        processing.setPipelineState(PipelineState.VECTORIZING.code());
        processing.setLockVersion(5);
        processing.setProgress(0);
        processing.setSourceHash("hash");
        processing.setStrategyCode("MARKDOWN_OPTIMIZED");
        processing.setPolicySnapshot(Map.of("maxTokens", 512));
        processing.setContextPolicy(Map.of("overlapEnabled", false, "overlapTokens", 40));
        processing.setExecutionMetadata(Map.of());

        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(1L);
        chunk.setPublicId(CHUNK_ID);
        chunk.setTenantId(TENANT_ID);
        chunk.setKnowledgeId(KNOWLEDGE_ID);
        chunk.setFileId(FILE_ID);
        chunk.setPosition(0);
        chunk.setContent(body);
        chunk.setContentHash("content-hash");
        chunk.setSectionPath(List.of());
        chunk.setStatus(ChunkStatus.INDEXING.code());
        chunk.setVectorId(OLD_VECTOR_ID);
        chunk.setIndexingLockVersion(5);
        chunk.setLockVersion(2);
        UUID pending = ChunkVectorWorker.vectorGenerationId(
                TENANT_ID, KNOWLEDGE_ID, FILE_ID, 5, CHUNK_ID, 2);
        chunk.setPendingVectorId(pending);

        File file = new File();
        file.setId(FILE_ID);
        file.setPublicId(FILE_PUBLIC_ID);
        file.setPath("unused-for-legacy-test-job");
        file.setType("md");

        FileProcessingMapper processingMapper = mock(FileProcessingMapper.class);
        FileMapper fileMapper = mock(FileMapper.class);
        DocumentChunkMapper chunkMapper = mock(DocumentChunkMapper.class);
        FileProcessingService stateService = mock(FileProcessingService.class);
        ChunkContextEnricher enricher = mock(ChunkContextEnricher.class);
        TokenCounter tokens = mock(TokenCounter.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        InMemoryGateway gateway = new InMemoryGateway(crashAfterAdd);
        gateway.values.put(OLD_VECTOR_ID, "old index");
        ActiveAwareLifecycle lifecycle = new ActiveAwareLifecycle(chunk, gateway);

        when(processingMapper.findScopedForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                .thenReturn(processing);
        when(fileMapper.selectById(FILE_ID)).thenReturn(file);
        when(chunkMapper.findByFileForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                .thenAnswer(invocation -> List.of(chunk));
        when(tokens.count(any())).thenReturn(4);
        when(chunkMapper.update(any(DocumentChunk.class), any(Wrapper.class)))
                .thenAnswer(invocation -> applyPatch(
                        chunk, invocation.getArgument(0), invocation.getArgument(1)));
        when(stateService.transition(anyLong(), anyLong(), any(), any(), anyInt()))
                .thenAnswer(invocation -> transition(processing, invocation.getArgument(2),
                        invocation.getArgument(3), invocation.getArgument(4)));
        AtomicInteger transaction = new AtomicInteger();
        doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            if (uncertainCommit && transaction.getAndIncrement() == 0) {
                throw new IllegalStateException("commit result unavailable");
            }
            return null;
        }).when(transactions).executeWithoutResult(any());

        ChunkVectorWorker worker = new ChunkVectorWorker(processingMapper, fileMapper, chunkMapper,
                stateService, enricher, tokens, gateway, transactions,
                new ChunkRuntimePolicyResolver(), path -> "hash", lifecycle);
        ChunkVectorWorker.ChunkSnapshot snapshot =
                ChunkVectorWorker.ChunkSnapshot.fromIndexing(chunk);
        ChunkVectorWorker.PreparedChunk prepared = ChunkVectorWorker.PreparedChunk.from(
                new EnrichedChunk(snapshot.detached(), null, null, 0, indexContent));
        ChunkVectorWorker.BatchJob job = new ChunkVectorWorker.BatchJob(
                TENANT_ID, KNOWLEDGE_ID, FILE_ID, 5, "hash", 512,
                ChunkVectorWorker.FileSnapshot.from(file), List.of(snapshot), List.of(snapshot),
                null, List.of(prepared));
        return new Fixture(worker, job, processing, chunk, gateway, lifecycle);
    }

    private static int applyPatch(DocumentChunk chunk, DocumentChunk patch, Wrapper<?> wrapper) {
        if (!Integer.valueOf(ChunkStatus.INDEXING.code()).equals(chunk.getStatus())
                || !Integer.valueOf(5).equals(chunk.getIndexingLockVersion())
                || chunk.getPendingVectorId() == null
                || !matchesCurrentOwner(wrapper, chunk)
                || !chunk.getPendingVectorId().equals(patch.getVectorId())) {
            return 0;
        }
        chunk.setStatus(patch.getStatus());
        chunk.setVectorId(patch.getVectorId());
        chunk.setIndexContent(patch.getIndexContent());
        chunk.setPendingVectorId(null);
        chunk.setIndexingLockVersion(null);
        chunk.setLockVersion(chunk.getLockVersion() + 1);
        return 1;
    }

    private static boolean matchesCurrentOwner(Wrapper<?> wrapper, DocumentChunk chunk) {
        if (!(wrapper instanceof AbstractWrapper<?, ?, ?> abstractWrapper)) {
            return false;
        }
        Map<String, Object> required = new LinkedHashMap<>();
        required.put("id", chunk.getId());
        required.put("tenant_id", chunk.getTenantId());
        required.put("knowledge_id", chunk.getKnowledgeId());
        required.put("file_id", chunk.getFileId());
        required.put("public_id", chunk.getPublicId());
        required.put("status", chunk.getStatus());
        required.put("lock_version", chunk.getLockVersion());
        required.put("indexing_lock_version", chunk.getIndexingLockVersion());
        required.put("vector_id", chunk.getVectorId());
        required.put("pending_vector_id", chunk.getPendingVectorId());
        required.put("content_hash", chunk.getContentHash());
        String sql = wrapper.getSqlSegment();
        Map<String, Object> parameters = abstractWrapper.getParamNameValuePairs();
        for (Map.Entry<String, Object> condition : required.entrySet()) {
            Pattern pattern = Pattern.compile("(?i)(?:^|[^a-z0-9_])"
                    + Pattern.quote(condition.getKey())
                    + "\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)}");
            Matcher matcher = pattern.matcher(sql);
            if (!matcher.find()
                    || !Objects.equals(parameters.get(matcher.group(1)), condition.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static FileProcessingService.Transition transition(
            FileProcessing processing, PipelineState expected, PipelineState target, int lock) {
        if (!Integer.valueOf(expected.code()).equals(processing.getPipelineState())
                || !Integer.valueOf(lock).equals(processing.getLockVersion())) {
            throw ChunkingException.conflict("stale transition");
        }
        processing.setPipelineState(target.code());
        processing.setLockVersion(lock + 1);
        return new FileProcessingService.Transition(KNOWLEDGE_ID, FILE_ID, expected, target,
                lock + 1, processing.getProgress(), null, null);
    }

    private record Fixture(ChunkVectorWorker worker, ChunkVectorWorker.BatchJob job,
                           FileProcessing processing, DocumentChunk chunk,
                           InMemoryGateway gateway, ActiveAwareLifecycle lifecycle) {
    }

    private static final class InMemoryGateway implements ChunkVectorGateway {
        private final Map<UUID, String> values = new LinkedHashMap<>();
        private final boolean crashAfterAdd;
        private final AtomicInteger addInvocations = new AtomicInteger();

        private InMemoryGateway(boolean crashAfterAdd) {
            this.crashAfterAdd = crashAfterAdd;
        }

        @Override
        public void delete(UUID vectorId) {
            values.remove(vectorId);
        }

        @Override
        public void deleteAll(List<UUID> vectorIds) {
            vectorIds.forEach(values::remove);
        }

        @Override
        public void add(List<VectorDocument> documents) {
            addInvocations.incrementAndGet();
            documents.forEach(document -> values.put(document.vectorId(), document.indexContent()));
            if (crashAfterAdd) {
                throw new SimulatedProcessDeath();
            }
        }
    }

    private static final class ActiveAwareLifecycle implements ChunkVectorLifecycle {
        private final DocumentChunk chunk;
        private final InMemoryGateway gateway;
        private final Set<CleanupObligation> queued = new LinkedHashSet<>();

        private ActiveAwareLifecycle(DocumentChunk chunk, InMemoryGateway gateway) {
            this.chunk = chunk;
            this.gateway = gateway;
        }

        @Override
        public void enqueue(Collection<CleanupObligation> obligations) {
            queued.addAll(obligations);
        }

        @Override
        public void enqueuePendingOwner(long tenantId, long knowledgeId, long fileId,
                                        int indexingLockVersion) {
            if (Integer.valueOf(indexingLockVersion).equals(chunk.getIndexingLockVersion())
                    && chunk.getPendingVectorId() != null) {
                queued.add(new CleanupObligation(chunk.getPendingVectorId(), tenantId,
                        knowledgeId, fileId, chunk.getPublicId()));
            }
        }

        @Override
        public void resetAbandonedClaims() {
        }

        @Override
        public void drain() {
            for (CleanupObligation obligation : List.copyOf(queued)) {
                if (obligation.vectorId().equals(chunk.getVectorId())
                        || obligation.vectorId().equals(chunk.getPendingVectorId())) {
                    queued.remove(obligation);
                    continue;
                }
                gateway.delete(obligation.vectorId());
                queued.remove(obligation);
            }
        }
    }

    private static final class SimulatedProcessDeath extends Error {
    }
}
