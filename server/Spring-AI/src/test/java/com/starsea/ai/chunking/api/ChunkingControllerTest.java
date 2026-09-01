package com.starsea.ai.chunking.api;

import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.chunking.model.ChunkPolicy;
import com.starsea.ai.chunking.model.PipelineState;
import com.starsea.ai.chunking.preview.ChunkPreviewService;
import com.starsea.ai.chunking.preview.ChunkPreviewWorker;
import com.starsea.ai.chunking.processing.ChunkTaskDispatcher;
import com.starsea.ai.chunking.processing.FileProcessingService;
import com.starsea.ai.chunking.registry.ChunkStrategyDescriptor;
import com.starsea.ai.chunking.registry.ChunkStrategyRegistry;
import com.starsea.ai.chunking.registry.DocumentStructureParserRegistry;
import com.starsea.ai.chunking.spi.ChunkPlanningStrategy;
import com.starsea.ai.chunking.spi.DocumentStructureParser;
import com.starsea.ai.config.GlobalExceptionHandler;
import com.starsea.ai.domain.File;
import com.starsea.ai.domain.FileProcessing;
import com.starsea.ai.mapper.DocumentChunkMapper;
import com.starsea.ai.mapper.FileMapper;
import com.starsea.ai.mapper.FileProcessingMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChunkingControllerTest {

    private static final long KNOWLEDGE_ID = 10L;
    private static final long FILE_ID = 20L;

    @TempDir
    Path tempDir;

    private FileProcessingMapper processingMapper;
    private FileMapper fileMapper;
    private ChunkTaskDispatcher dispatcher;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 7L, 1L, "tenant_admin", "jti"));
        processingMapper = mock(FileProcessingMapper.class);
        fileMapper = mock(FileMapper.class);
        dispatcher = mock(ChunkTaskDispatcher.class);
        DocumentChunkMapper chunkMapper = mock(DocumentChunkMapper.class);
        ChunkPreviewWorker worker = mock(ChunkPreviewWorker.class);

        ChunkPlanningStrategy markdownStrategy = mock(ChunkPlanningStrategy.class);
        when(markdownStrategy.code()).thenReturn("MARKDOWN_OPTIMIZED");
        when(markdownStrategy.supportedFileTypes()).thenReturn(Set.of("md", "markdown"));
        when(markdownStrategy.plannerVersion()).thenReturn("markdown-adaptive-v1");
        when(markdownStrategy.descriptor()).thenReturn(new ChunkStrategyDescriptor(
                "MARKDOWN_OPTIMIZED", "FILE_TYPE", Set.of("md", "markdown"),
                "markdown-adaptive-v1", List.of()));
        DocumentStructureParser markdownParser = mock(DocumentStructureParser.class);
        when(markdownParser.supportedFileTypes()).thenReturn(Set.of("md", "markdown"));

        ChunkPreviewService service = new ChunkPreviewService(
                processingMapper,
                fileMapper,
                chunkMapper,
                new ChunkStrategyRegistry(List.of(markdownStrategy)),
                new DocumentStructureParserRegistry(List.of(markdownParser)),
                dispatcher,
                worker);
        mockMvc = MockMvcBuilders.standaloneSetup(new ChunkingController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        Path source = tempDir.resolve("guide.md");
        Files.writeString(source, "# Guide\n\nA real paragraph.\n");
        when(processingMapper.selectById(FILE_ID)).thenReturn(processing(1L, KNOWLEDGE_ID));
        when(fileMapper.selectById(FILE_ID)).thenReturn(file(source, "md"));
        when(chunkMapper.findByFile(FILE_ID, 1L, KNOWLEDGE_ID)).thenReturn(List.of());
    }

    @AfterEach
    void clearAuth() {
        AuthContext.clear();
    }

    @Test
    void markdown_capability_exposes_only_the_registered_markdown_strategy() throws Exception {
        mockMvc.perform(get("/knowledge/{knowledgeId}/files/{fileId}/chunk-strategies",
                        KNOWLEDGE_ID, FILE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileType").value("md"))
                .andExpect(jsonPath("$.strategies.length()").value(1))
                .andExpect(jsonPath("$.strategies[0].code").value("MARKDOWN_OPTIMIZED"));
    }

    @Test
    void valid_preview_request_is_accepted_for_asynchronous_generation() throws Exception {
        mockMvc.perform(post("/knowledge/{knowledgeId}/files/{fileId}/chunk-preview",
                        KNOWLEDGE_ID, FILE_ID)
                        .contentType("application/json")
                        .content("""
                                {"strategyCode":"MARKDOWN_OPTIMIZED",
                                 "strategyConfig":{"minTokens":100,"targetTokens":400,"maxTokens":512},
                                 "replaceEditedDrafts":false,"lockVersion":0}
                                """))
                .andExpect(status().isAccepted());
    }

    @Test
    void forged_frontend_only_strategy_is_unprocessable() throws Exception {
        mockMvc.perform(post("/knowledge/{knowledgeId}/files/{fileId}/chunk-preview",
                        KNOWLEDGE_ID, FILE_ID)
                        .contentType("application/json")
                        .content("""
                                {"strategyCode":"GENERAL",
                                 "strategyConfig":{"minTokens":100,"targetTokens":400,"maxTokens":512},
                                 "replaceEditedDrafts":false,"lockVersion":0}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void file_outside_tenant_and_knowledge_scope_is_not_found() throws Exception {
        when(processingMapper.selectById(FILE_ID)).thenReturn(processing(2L, 11L));

        mockMvc.perform(get("/knowledge/{knowledgeId}/files/{fileId}/processing",
                        KNOWLEDGE_ID, FILE_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalid_token_budget_is_unprocessable_instead_of_bad_json() throws Exception {
        mockMvc.perform(post("/knowledge/{knowledgeId}/files/{fileId}/chunk-preview",
                        KNOWLEDGE_ID, FILE_ID)
                        .contentType("application/json")
                        .content("""
                                {"strategyCode":"MARKDOWN_OPTIMIZED",
                                 "strategyConfig":{"minTokens":401,"targetTokens":400,"maxTokens":512},
                                 "replaceEditedDrafts":false,"lockVersion":0}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void dispatcher_response_status_exception_remains_an_actual_http_503() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "queue full"))
                .when(dispatcher).dispatch(eq(KNOWLEDGE_ID), eq(FILE_ID), eq(PipelineState.UPLOADED),
                        eq(PipelineState.CHUNKING), eq(0), any(Runnable.class));

        mockMvc.perform(post("/knowledge/{knowledgeId}/files/{fileId}/chunk-preview",
                        KNOWLEDGE_ID, FILE_ID)
                        .contentType("application/json")
                        .content("""
                                {"strategyCode":"MARKDOWN_OPTIMIZED",
                                 "strategyConfig":{"minTokens":100,"targetTokens":400,"maxTokens":512},
                                 "replaceEditedDrafts":false,"lockVersion":0}
                                """))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void dispatcher_ownership_race_remains_an_actual_http_404() throws Exception {
        doThrow(new FileProcessingService.OwnershipException("scope changed"))
                .when(dispatcher).dispatch(eq(KNOWLEDGE_ID), eq(FILE_ID), eq(PipelineState.UPLOADED),
                        eq(PipelineState.CHUNKING), eq(0), any(Runnable.class));

        performValidPreview().andExpect(status().isNotFound());
    }

    @Test
    void dispatcher_state_or_lock_race_remains_an_actual_http_409() throws Exception {
        doThrow(new FileProcessingService.StateConflictException("lock changed"))
                .when(dispatcher).dispatch(eq(KNOWLEDGE_ID), eq(FILE_ID), eq(PipelineState.UPLOADED),
                        eq(PipelineState.CHUNKING), eq(0), any(Runnable.class));

        performValidPreview().andExpect(status().isConflict());
    }

    private org.springframework.test.web.servlet.ResultActions performValidPreview() throws Exception {
        return mockMvc.perform(post("/knowledge/{knowledgeId}/files/{fileId}/chunk-preview",
                        KNOWLEDGE_ID, FILE_ID)
                .contentType("application/json")
                .content("""
                        {"strategyCode":"MARKDOWN_OPTIMIZED",
                         "strategyConfig":{"minTokens":100,"targetTokens":400,"maxTokens":512},
                         "replaceEditedDrafts":false,"lockVersion":0}
                        """));
    }

    private static FileProcessing processing(long tenantId, long knowledgeId) {
        FileProcessing processing = new FileProcessing();
        processing.setFileId(FILE_ID);
        processing.setTenantId(tenantId);
        processing.setKnowledgeId(knowledgeId);
        processing.setPipelineState(PipelineState.UPLOADED.code());
        processing.setProgress(0);
        processing.setLockVersion(0);
        return processing;
    }

    private static File file(Path source, String type) {
        File file = new File();
        file.setId(FILE_ID);
        file.setFileName(source.getFileName().toString());
        file.setType(type);
        file.setPath(source.toString());
        return file;
    }
}
