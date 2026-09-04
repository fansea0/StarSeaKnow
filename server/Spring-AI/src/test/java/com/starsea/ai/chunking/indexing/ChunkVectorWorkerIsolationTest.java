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
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChunkVectorWorkerIsolationTest {

    private static final long TENANT_ID = 1L;
    private static final long KNOWLEDGE_ID = 10L;
    private static final long FILE_ID = 20L;
    private static final UUID FILE_PUBLIC_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID CHUNK_PUBLIC_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OLD_VECTOR_ID =
            UUID.fromString("77777777-7777-7777-7777-777777777777");

    @TempDir
    Path tempDir;

    @Test
    void stale_job_resuming_while_retry_is_vectorizing_cannot_restore_or_delete_the_retry()
            throws Exception {
        RaceFixture fixture = fixture(true);
        Thread stale = new Thread(() -> fixture.worker.vectorizeBatch(fixture.jobA), "stale-vector-A");
        stale.start();
        assertTrue(fixture.gateway.aEntered.await(5, TimeUnit.SECONDS));

        fixture.recoverAAndStartB();
        Thread retry = new Thread(() -> fixture.worker.vectorizeBatch(fixture.jobB), "retry-vector-B");
        retry.start();
        assertTrue(fixture.gateway.bEntered.await(5, TimeUnit.SECONDS));

        fixture.gateway.releaseA.countDown();
        stale.join(5_000);
        assertFalse(stale.isAlive());
        assertEquals(PipelineState.VECTORIZING.code(), fixture.processing.getPipelineState());
        assertEquals(ChunkStatus.INDEXING.code(), fixture.chunk.getStatus());
        assertEquals(7, fixture.chunk.getIndexingLockVersion());

        fixture.gateway.releaseB.countDown();
        retry.join(5_000);
        assertFalse(retry.isAlive());
        fixture.assertRetryCompleted();
    }

    @Test
    void stale_job_resuming_after_retry_completed_cannot_delete_the_completed_generation()
            throws Exception {
        RaceFixture fixture = fixture(false);
        Thread stale = new Thread(() -> fixture.worker.vectorizeBatch(fixture.jobA), "stale-vector-A");
        stale.start();
        assertTrue(fixture.gateway.aEntered.await(5, TimeUnit.SECONDS));

        fixture.recoverAAndStartB();
        fixture.worker.vectorizeBatch(fixture.jobB);
        fixture.assertRetryCompleted();

        fixture.gateway.releaseA.countDown();
        stale.join(5_000);
        assertFalse(stale.isAlive());
        fixture.assertRetryCompleted();
    }

    private RaceFixture fixture(boolean blockB) throws Exception {
        Path source = tempDir.resolve("source.md");
        Files.writeString(source, "stable source bytes");
        String sourceHash = SourceHashing.sha256(source);
        FileProcessing processing = processing(5);
        processing.setSourceHash(sourceHash);
        DocumentChunk chunk = chunk("A body", "hash-a", 1, 5);
        chunk.setPendingVectorId(ChunkVectorWorker.vectorGenerationId(
                TENANT_ID, KNOWLEDGE_ID, FILE_ID, 5, CHUNK_PUBLIC_ID, 1));
        File file = new File();
        file.setId(FILE_ID);
        file.setPublicId(FILE_PUBLIC_ID);
        file.setPath(source.toString());
        file.setType("md");

        FileProcessingMapper processingMapper = mock(FileProcessingMapper.class);
        FileMapper fileMapper = mock(FileMapper.class);
        DocumentChunkMapper chunkMapper = mock(DocumentChunkMapper.class);
        FileProcessingService stateService = mock(FileProcessingService.class);
        ChunkContextEnricher enricher = mock(ChunkContextEnricher.class);
        TokenCounter tokens = mock(TokenCounter.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        LatchingGateway gateway = new LatchingGateway(blockB);
        chunk.setVectorId(OLD_VECTOR_ID);
        gateway.seed(OLD_VECTOR_ID, "old active index");
        when(processingMapper.findScopedForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                .thenReturn(processing);
        when(fileMapper.selectById(FILE_ID)).thenReturn(file);
        when(chunkMapper.findByFileForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                .thenAnswer(invocation -> List.of(chunk));
        when(tokens.count(any())).thenAnswer(invocation -> {
            String value = invocation.getArgument(0);
            return value.codePointCount(0, value.length());
        });
        doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());
        when(chunkMapper.update(any(DocumentChunk.class), any(Wrapper.class)))
                .thenAnswer(invocation -> applyChunkPatch(
                        chunk, invocation.getArgument(0), invocation.getArgument(1)));
        when(stateService.transition(anyLong(), anyLong(), any(), any(), anyInt()))
                .thenAnswer(invocation -> transition(processing, invocation.getArgument(2),
                        invocation.getArgument(3), invocation.getArgument(4)));
        when(stateService.fail(anyLong(), anyLong(), any(), anyInt(), anyInt(), any()))
                .thenAnswer(invocation -> transition(processing, invocation.getArgument(2),
                        PipelineState.FAILED, invocation.getArgument(3)));

        ChunkRuntimePolicyResolver resolver = new ChunkRuntimePolicyResolver();
        ChunkVectorWorker worker = new ChunkVectorWorker(processingMapper, fileMapper, chunkMapper,
                stateService, enricher, tokens, gateway, transactions, resolver);
        ChunkVectorWorker.BatchJob jobA = job(processing, chunk, file, sourceHash, "A index", resolver);
        return new RaceFixture(worker, gateway, processing, chunk, file, sourceHash, resolver, jobA);
    }

    private ChunkVectorWorker.BatchJob job(FileProcessing processing, DocumentChunk chunk,
                                           File file, String sourceHash, String index,
                                           ChunkRuntimePolicyResolver resolver) {
        ChunkVectorWorker.ChunkSnapshot snapshot =
                ChunkVectorWorker.ChunkSnapshot.fromIndexing(chunk);
        DocumentChunk detached = snapshot.detached();
        ChunkVectorWorker.PreparedChunk prepared = ChunkVectorWorker.PreparedChunk.from(
                new EnrichedChunk(detached, null, null, 0, index));
        return new ChunkVectorWorker.BatchJob(TENANT_ID, KNOWLEDGE_ID, FILE_ID,
                processing.getLockVersion(), sourceHash, 512,
                ChunkVectorWorker.FileSnapshot.from(file), List.of(snapshot), List.of(snapshot),
                ChunkVectorWorker.ProcessingSnapshot.from(processing, resolver), List.of(prepared));
    }

    private static int applyChunkPatch(DocumentChunk chunk, DocumentChunk patch,
                                       Wrapper<?> wrapper) {
        synchronized (chunk) {
            UUID expectedPending = ChunkVectorWorker.vectorGenerationId(
                    chunk.getTenantId(), chunk.getKnowledgeId(), chunk.getFileId(),
                    chunk.getIndexingLockVersion(), chunk.getPublicId(), chunk.getLockVersion());
            if (!Integer.valueOf(ChunkStatus.INDEXING.code()).equals(chunk.getStatus())
                    || !expectedPending.equals(chunk.getPendingVectorId())
                    || !matchesCurrentOwner(wrapper, chunk)
                    || (Integer.valueOf(ChunkStatus.ACTIVE.code()).equals(patch.getStatus())
                    && !expectedPending.equals(patch.getVectorId()))) {
                return 0;
            }
            chunk.setStatus(patch.getStatus());
            chunk.setIndexContent(patch.getIndexContent());
            chunk.setVectorId(patch.getVectorId());
            if (Integer.valueOf(ChunkStatus.ACTIVE.code()).equals(patch.getStatus())
                    || Integer.valueOf(ChunkStatus.DRAFT.code()).equals(patch.getStatus())) {
                chunk.setIndexingLockVersion(null);
                chunk.setPendingVectorId(null);
            }
            chunk.setLastError(patch.getLastError());
            chunk.setLockVersion(chunk.getLockVersion() + 1);
            return 1;
        }
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
            FileProcessing processing, PipelineState expected, PipelineState target, int lockVersion) {
        synchronized (processing) {
            if (!Integer.valueOf(expected.code()).equals(processing.getPipelineState())
                    || !Integer.valueOf(lockVersion).equals(processing.getLockVersion())) {
                throw ChunkingException.conflict("stale test transition");
            }
            processing.setPipelineState(target.code());
            processing.setLockVersion(lockVersion + 1);
            processing.setFailedFromState(target == PipelineState.FAILED ? expected.code() : null);
            return new FileProcessingService.Transition(KNOWLEDGE_ID, FILE_ID, expected, target,
                    lockVersion + 1, processing.getProgress(), null, null);
        }
    }

    private static FileProcessing processing(int lockVersion) {
        FileProcessing processing = new FileProcessing();
        processing.setFileId(FILE_ID);
        processing.setTenantId(TENANT_ID);
        processing.setKnowledgeId(KNOWLEDGE_ID);
        processing.setPipelineState(PipelineState.VECTORIZING.code());
        processing.setLockVersion(lockVersion);
        processing.setProgress(0);
        processing.setSourceHash("unused-by-fixture-construction");
        processing.setStrategyCode("MARKDOWN_OPTIMIZED");
        processing.setPlannerVersion("markdown-adaptive-v1");
        processing.setPolicySnapshot(Map.of("maxTokens", 512));
        processing.setContextPolicy(Map.of("overlapEnabled", false, "overlapTokens", 40));
        processing.setExecutionMetadata(Map.of());
        return processing;
    }

    private static DocumentChunk chunk(String content, String hash, int lockVersion,
                                       int indexingLockVersion) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(1L);
        chunk.setPublicId(CHUNK_PUBLIC_ID);
        chunk.setTenantId(TENANT_ID);
        chunk.setKnowledgeId(KNOWLEDGE_ID);
        chunk.setFileId(FILE_ID);
        chunk.setPosition(0);
        chunk.setContent(content);
        chunk.setContentHash(hash);
        chunk.setSectionPath(List.of());
        chunk.setStatus(ChunkStatus.INDEXING.code());
        chunk.setLockVersion(lockVersion);
        chunk.setIndexingLockVersion(indexingLockVersion);
        return chunk;
    }

    private final class RaceFixture {
        private final ChunkVectorWorker worker;
        private final LatchingGateway gateway;
        private final FileProcessing processing;
        private final DocumentChunk chunk;
        private final File file;
        private final String sourceHash;
        private final ChunkRuntimePolicyResolver resolver;
        private final ChunkVectorWorker.BatchJob jobA;
        private ChunkVectorWorker.BatchJob jobB;

        private RaceFixture(ChunkVectorWorker worker, LatchingGateway gateway,
                            FileProcessing processing, DocumentChunk chunk, File file,
                            String sourceHash, ChunkRuntimePolicyResolver resolver,
                            ChunkVectorWorker.BatchJob jobA) {
            this.worker = worker;
            this.gateway = gateway;
            this.processing = processing;
            this.chunk = chunk;
            this.file = file;
            this.sourceHash = sourceHash;
            this.resolver = resolver;
            this.jobA = jobA;
        }

        private void recoverAAndStartB() {
            processing.setPipelineState(PipelineState.FAILED.code());
            processing.setFailedFromState(PipelineState.VECTORIZING.code());
            processing.setLockVersion(6);
            processing.setSourceHash(sourceHash);
            chunk.setStatus(ChunkStatus.INDEXING.code());
            chunk.setContent("B body");
            chunk.setContentHash("hash-b");
            chunk.setIndexContent(null);
            chunk.setLockVersion(3);
            chunk.setIndexingLockVersion(7);
            chunk.setPendingVectorId(ChunkVectorWorker.vectorGenerationId(
                    TENANT_ID, KNOWLEDGE_ID, FILE_ID, 7, CHUNK_PUBLIC_ID, 3));
            processing.setPipelineState(PipelineState.VECTORIZING.code());
            processing.setFailedFromState(null);
            processing.setLockVersion(7);
            jobB = job(processing, chunk, file, sourceHash, "B index", resolver);
        }

        private void assertRetryCompleted() {
            assertEquals(PipelineState.COMPLETED.code(), processing.getPipelineState());
            assertEquals(ChunkStatus.ACTIVE.code(), chunk.getStatus());
            assertEquals("B index", chunk.getIndexContent());
            assertNotNull(chunk.getVectorId());
            assertEquals(Map.of(chunk.getVectorId(), "B index"), gateway.contents());
        }
    }

    private static final class LatchingGateway implements ChunkVectorGateway {
        private final Map<UUID, String> values = new LinkedHashMap<>();
        private final CountDownLatch aEntered = new CountDownLatch(1);
        private final CountDownLatch bEntered = new CountDownLatch(1);
        private final CountDownLatch releaseA = new CountDownLatch(1);
        private final CountDownLatch releaseB;

        private LatchingGateway(boolean blockB) {
            releaseB = new CountDownLatch(blockB ? 1 : 0);
        }

        @Override
        public synchronized void delete(UUID publicId) {
            values.remove(publicId);
        }

        @Override
        public void deleteAll(List<UUID> publicIds) {
            synchronized (this) {
                publicIds.forEach(values::remove);
            }
        }

        @Override
        public void add(List<VectorDocument> documents) {
            String text = documents.get(0).indexContent();
            CountDownLatch entered = text.startsWith("A") ? aEntered : bEntered;
            CountDownLatch release = text.startsWith("A") ? releaseA : releaseB;
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("vector write latch timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
            synchronized (this) {
                documents.forEach(document -> values.put(document.vectorId(), document.indexContent()));
            }
        }

        private synchronized Map<UUID, String> contents() {
            return Map.copyOf(values);
        }

        private synchronized void seed(UUID vectorId, String content) {
            values.put(vectorId, content);
        }
    }
}
