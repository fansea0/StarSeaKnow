package com.starsea.ai.chunking.preview;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.chunking.model.ChunkDraft;
import com.starsea.ai.chunking.model.ChunkPolicy;
import com.starsea.ai.chunking.model.ChunkStatus;
import com.starsea.ai.chunking.model.FileResource;
import com.starsea.ai.chunking.model.ParsedStructure;
import com.starsea.ai.chunking.model.PipelineState;
import com.starsea.ai.chunking.model.SourceLocator;
import com.starsea.ai.chunking.processing.FileProcessingService;
import com.starsea.ai.chunking.registry.ChunkStrategyRegistry;
import com.starsea.ai.chunking.registry.DocumentStructureParserRegistry;
import com.starsea.ai.chunking.spi.ChunkPlanningStrategy;
import com.starsea.ai.chunking.spi.DocumentStructureParser;
import com.starsea.ai.chunking.spi.TokenCounter;
import com.starsea.ai.domain.DocumentChunk;
import com.starsea.ai.domain.File;
import com.starsea.ai.domain.FileProcessing;
import com.starsea.ai.mapper.DocumentChunkMapper;
import com.starsea.ai.mapper.FileMapper;
import com.starsea.ai.mapper.FileProcessingMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChunkPreviewWorkerTest {

    @TempDir
    Path tempDir;

    private FileMapper fileMapper;
    private FileProcessingMapper processingMapper;
    private DocumentStructureParser parser;
    private ChunkPlanningStrategy planner;
    private ChunkPreviewPersistenceService persistence;
    private FileProcessingService processingService;
    private ChunkPreviewWorker worker;
    private Path source;

    @BeforeEach
    void setUp() throws Exception {
        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 7L, 1L, "tenant_admin", "jti"));
        source = tempDir.resolve("source.md");
        Files.writeString(source, "# Title\n\nExact source bytes.\n");
        fileMapper = mock(FileMapper.class);
        processingMapper = mock(FileProcessingMapper.class);
        parser = mock(DocumentStructureParser.class);
        planner = mock(ChunkPlanningStrategy.class);
        persistence = mock(ChunkPreviewPersistenceService.class);
        processingService = mock(FileProcessingService.class);
        when(parser.supportedFileTypes()).thenReturn(Set.of("md"));
        when(planner.code()).thenReturn("MARKDOWN_OPTIMIZED");
        when(planner.supportedFileTypes()).thenReturn(Set.of("md"));
        when(planner.plannerVersion()).thenReturn("markdown-adaptive-v1");
        when(fileMapper.selectById(20L)).thenReturn(file(source));
        when(processingMapper.selectById(20L)).thenReturn(processing());
        TokenCounter tokenCounter = mock(TokenCounter.class);
        when(tokenCounter.id()).thenReturn("BAAI/bge-base-zh-v1.5@test-sha");
        worker = new ChunkPreviewWorker(
                fileMapper,
                processingMapper,
                new DocumentStructureParserRegistry(List.of(parser)),
                new ChunkStrategyRegistry(List.of(planner)),
                tokenCounter,
                persistence,
                processingService);
    }

    @AfterEach
    void clearAuth() {
        AuthContext.clear();
    }

    @Test
    void hashes_exact_source_selects_parser_and_planner_independently_and_persists_snapshot() {
        FileResource resource = new FileResource(1L, 10L, 20L, null, "source.md", "md", source);
        ParsedStructure structure = new ParsedStructure(resource, List.of());
        ChunkDraft draft = draft("Body", 3);
        when(parser.parse(any(FileResource.class))).thenReturn(structure);
        when(planner.plan(structure, new ChunkPolicy(100, 400, 512))).thenReturn(List.of(draft));

        worker.generate(job());

        verify(parser).parse(any(FileResource.class));
        verify(planner).plan(structure, new ChunkPolicy(100, 400, 512));
        verify(persistence).replace(
                eq(job()),
                eq("cafc00b3438fefaa916b121d6475c4466514fdf3b67d2d33a340017e0a341619"),
                eq("markdown-adaptive-v1"),
                eq(Map.of(
                        "minTokens", 100,
                        "targetTokens", 400,
                        "maxTokens", 512,
                        "tokenizer", "BAAI/bge-base-zh-v1.5@test-sha")),
                eq(List.of(draft)));
    }

    @Test
    void parser_failure_writes_failed_from_chunking_without_persisting_chunks() {
        when(parser.parse(any(FileResource.class))).thenThrow(new IllegalStateException("broken markdown"));

        worker.generate(job());

        verify(persistence, never()).replace(any(), any(), any(), any(), any());
        verify(processingService).fail(10L, 20L, PipelineState.CHUNKING, 1, 0, "broken markdown");
    }

    @Test
    void persistence_failure_is_reported_after_transaction_boundary_rolls_back() throws Exception {
        ParsedStructure structure = new ParsedStructure(
                new FileResource(1L, 10L, 20L, null, "source.md", "md", source), List.of());
        when(parser.parse(any(FileResource.class))).thenReturn(structure);
        when(planner.plan(eq(structure), any(ChunkPolicy.class))).thenReturn(List.of(draft("Body", 3)));
        doThrow(new IllegalStateException("database unavailable"))
                .when(persistence).replace(any(), any(), any(), any(), any());

        worker.generate(job());

        verify(processingService).fail(10L, 20L, PipelineState.CHUNKING, 1, 0, "database unavailable");
        Method replace = ChunkPreviewPersistenceService.class.getMethod(
                "replace", ChunkPreviewWorker.Job.class, String.class, String.class,
                Map.class, List.class);
        assertTrue(replace.isAnnotationPresent(Transactional.class));
    }

    @Test
    void transactional_replacement_saves_drafts_then_moves_chunking_to_chunked() {
        DocumentChunkMapper chunkMapper = mock(DocumentChunkMapper.class);
        FileProcessingService realProcessingService = mock(FileProcessingService.class);
        TokenCounter tokenCounter = mock(TokenCounter.class);
        when(chunkMapper.findByFile(20L, 1L, 10L)).thenReturn(List.of());
        when(processingMapper.update(any(FileProcessing.class), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(chunkMapper.insert(any(DocumentChunk.class))).thenReturn(1);
        ChunkPreviewPersistenceService service = new ChunkPreviewPersistenceService(
                chunkMapper, processingMapper, realProcessingService);

        service.replace(job(), "source-hash", "planner-v1",
                Map.of("tokenizer", "exact-tokenizer"), List.of(draft("First", 2), draft("Second", 3)));

        var captor = org.mockito.ArgumentCaptor.forClass(DocumentChunk.class);
        verify(chunkMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        List<DocumentChunk> saved = captor.getAllValues();
        assertEquals(List.of(0, 1), saved.stream().map(DocumentChunk::getPosition).toList());
        assertEquals(List.of(ChunkStatus.DRAFT.code(), ChunkStatus.DRAFT.code()),
                saved.stream().map(DocumentChunk::getStatus).toList());
        assertTrue(saved.stream().allMatch(chunk -> !chunk.getContent().isBlank()));
        assertTrue(saved.stream().allMatch(chunk -> chunk.getPublicId() != null));
        assertTrue(saved.stream().allMatch(chunk -> chunk.getOverlapTokenCount() == 0));
        assertTrue(saved.stream().allMatch(chunk -> chunk.getIndexContent() == null));

        var metadata = org.mockito.ArgumentCaptor.forClass(FileProcessing.class);
        verify(processingMapper).update(metadata.capture(), any(LambdaUpdateWrapper.class));
        assertEquals("source-hash", metadata.getValue().getSourceHash());
        assertEquals("MARKDOWN_OPTIMIZED", metadata.getValue().getStrategyCode());
        assertEquals("planner-v1", metadata.getValue().getPlannerVersion());
        assertEquals(Map.of("tokenizer", "exact-tokenizer"), metadata.getValue().getPolicySnapshot());
        verify(realProcessingService).transition(
                10L, 20L, PipelineState.CHUNKING, PipelineState.CHUNKED, 1);
    }

    private static ChunkPreviewWorker.Job job() {
        return new ChunkPreviewWorker.Job(
                10L, 20L, "MARKDOWN_OPTIMIZED", new ChunkPolicy(100, 400, 512), false, 1);
    }

    private static ChunkDraft draft(String content, int tokens) {
        return new ChunkDraft(
                List.of("Title"),
                content,
                new SourceLocator("markdown", List.of("b1"), 0, content.length(),
                        1, 1, null, null, List.of()),
                tokens,
                Map.of("end", "PARAGRAPH_END"));
    }

    private static File file(Path path) {
        File file = new File();
        file.setId(20L);
        file.setFileName("source.md");
        file.setType("md");
        file.setPath(path.toString());
        return file;
    }

    private static FileProcessing processing() {
        FileProcessing processing = new FileProcessing();
        processing.setFileId(20L);
        processing.setTenantId(1L);
        processing.setKnowledgeId(10L);
        processing.setPipelineState(PipelineState.CHUNKING.code());
        processing.setLockVersion(1);
        return processing;
    }
}
