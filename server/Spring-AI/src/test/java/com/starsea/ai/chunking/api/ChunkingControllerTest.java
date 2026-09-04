package com.starsea.ai.chunking.api;

import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.chunking.model.ChunkPolicy;
import com.starsea.ai.chunking.model.ContextConfig;
import com.starsea.ai.chunking.model.GeneralChunkConfig;
import com.starsea.ai.chunking.model.OverlapUnit;
import com.starsea.ai.chunking.model.PipelineState;
import com.starsea.ai.chunking.preview.ChunkPreviewService;
import com.starsea.ai.chunking.preview.ChunkCommandService;
import com.starsea.ai.chunking.preview.ChunkPreviewWorker;
import com.starsea.ai.chunking.processing.ChunkTaskDispatcher;
import com.starsea.ai.chunking.processing.FileProcessingService;
import com.starsea.ai.chunking.registry.ChunkStrategyDescriptor;
import com.starsea.ai.chunking.registry.ChunkInputProviderRegistry;
import com.starsea.ai.chunking.registry.ChunkStrategyRegistry;
import com.starsea.ai.chunking.registry.DocumentStructureParserRegistry;
import com.starsea.ai.chunking.spi.ChunkPlanningStrategy;
import com.starsea.ai.chunking.spi.ChunkInputProvider;
import com.starsea.ai.chunking.spi.DocumentStructureParser;
import com.starsea.ai.config.GlobalExceptionHandler;
import com.starsea.ai.domain.File;
import com.starsea.ai.domain.FileProcessing;
import com.starsea.ai.domain.DocumentChunk;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private DocumentChunkMapper chunkMapper;
    private ChunkPreviewWorker worker;
    private ChunkInputProvider generalInput;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 7L, 1L, "tenant_admin", "jti"));
        processingMapper = mock(FileProcessingMapper.class);
        fileMapper = mock(FileMapper.class);
        dispatcher = mock(ChunkTaskDispatcher.class);
        chunkMapper = mock(DocumentChunkMapper.class);
        worker = mock(ChunkPreviewWorker.class);

        ChunkPlanningStrategy markdownStrategy = mock(ChunkPlanningStrategy.class);
        when(markdownStrategy.code()).thenReturn("MARKDOWN_OPTIMIZED");
        when(markdownStrategy.supportedFileTypes()).thenReturn(Set.of("md", "markdown"));
        when(markdownStrategy.plannerVersion()).thenReturn("markdown-adaptive-v1");
        when(markdownStrategy.descriptor()).thenReturn(new ChunkStrategyDescriptor(
                "MARKDOWN_OPTIMIZED", "FILE_TYPE", Set.of("md", "markdown"),
                "markdown-adaptive-v1", List.of(),
                List.of(new ChunkStrategyDescriptor.ConfigField(
                        "enabled", "boolean", false, null, null, Map.of()),
                        new ChunkStrategyDescriptor.ConfigField(
                                "limit", "number", 40, 0, 512, Map.of("unit", "TOKENS"))),
                ContextConfig.markdownDefaults()));
        ChunkPlanningStrategy generalStrategy = mock(ChunkPlanningStrategy.class);
        when(generalStrategy.code()).thenReturn("GENERAL");
        when(generalStrategy.supportedFileTypes()).thenReturn(Set.of("*"));
        when(generalStrategy.plannerVersion()).thenReturn("general-deterministic-v1");
        when(generalStrategy.descriptor()).thenReturn(new ChunkStrategyDescriptor(
                "GENERAL", "GLOBAL", Set.of("*"), "general-deterministic-v1",
                List.of(),
                List.of(new ChunkStrategyDescriptor.ConfigField(
                        "enabled", "boolean", true, null, null, Map.of()),
                        new ChunkStrategyDescriptor.ConfigField(
                                "limit", "number", 40, 0, 1000,
                                Map.of("unit", "CHARACTERS"))),
                ContextConfig.generalDefaults()));
        DocumentStructureParser markdownParser = mock(DocumentStructureParser.class);
        when(markdownParser.supportedFileTypes()).thenReturn(Set.of("md", "markdown"));
        generalInput = mock(ChunkInputProvider.class);
        when(generalInput.strategyCode()).thenReturn("GENERAL");
        when(generalInput.supportedFileTypes()).thenReturn(Set.of("*"));
        when(generalInput.global()).thenReturn(true);
        when(generalInput.capability(any())).thenReturn(ChunkInputProvider.Capability.supported());
        ChunkInputProvider markdownInput = mock(ChunkInputProvider.class);
        when(markdownInput.strategyCode()).thenReturn("MARKDOWN_OPTIMIZED");
        when(markdownInput.supportedFileTypes()).thenReturn(Set.of("md", "markdown"));
        when(markdownInput.capability(any())).thenReturn(ChunkInputProvider.Capability.supported());

        ChunkPreviewService service = new ChunkPreviewService(
                processingMapper,
                fileMapper,
                chunkMapper,
                new ChunkStrategyRegistry(List.of(generalStrategy, markdownStrategy),
                        new com.fasterxml.jackson.databind.ObjectMapper().configure(
                                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                                false)),
                new DocumentStructureParserRegistry(List.of(markdownParser)),
                new ChunkInputProviderRegistry(List.of(generalInput, markdownInput)),
                dispatcher,
                worker);
        mockMvc = MockMvcBuilders.standaloneSetup(new ChunkingController(
                        service, mock(ChunkCommandService.class)))
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
    void markdown_capability_exposes_general_and_markdown_as_available() throws Exception {
        mockMvc.perform(get("/knowledge/{knowledgeId}/files/{fileId}/chunk-strategies",
                        KNOWLEDGE_ID, FILE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileType").value("md"))
                .andExpect(jsonPath("$.strategies.length()").value(2))
                .andExpect(jsonPath("$.strategies[0].code").value("GENERAL"))
                .andExpect(jsonPath("$.strategies[0].available").value(true))
                .andExpect(jsonPath("$.strategies[0].defaultContextConfig.unit").value("CHARACTERS"))
                .andExpect(jsonPath("$.strategies[0].contextConfigFields[1].key").value("limit"))
                .andExpect(jsonPath("$.strategies[0].contextConfigFields[1].min").value(0))
                .andExpect(jsonPath("$.strategies[0].contextConfigFields[1].max").value(1000))
                .andExpect(jsonPath("$.strategies[1].code").value("MARKDOWN_OPTIMIZED"))
                .andExpect(jsonPath("$.strategies[1].available").value(true))
                .andExpect(jsonPath("$.strategies[1].contextConfigFields[1].max").value(512));
    }

    @Test
    void unavailable_general_capability_remains_visible_with_its_reason() throws Exception {
        when(generalInput.capability(any())).thenReturn(
                ChunkInputProvider.Capability.unavailable("No readable text extractor"));

        mockMvc.perform(get("/knowledge/{knowledgeId}/files/{fileId}/chunk-strategies",
                        KNOWLEDGE_ID, FILE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategies[0].code").value("GENERAL"))
                .andExpect(jsonPath("$.strategies[0].available").value(false))
                .andExpect(jsonPath("$.strategies[0].reason").value("No readable text extractor"))
                .andExpect(jsonPath("$.strategies[1].available").value(true));
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
    void zero_byte_source_is_rejected_before_preview_dispatch() throws Exception {
        Path empty = tempDir.resolve("empty.md");
        Files.createFile(empty);
        when(fileMapper.selectById(FILE_ID)).thenReturn(file(empty, "md"));

        performValidPreview()
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.msg").value("The source document is empty"));

        verify(dispatcher, org.mockito.Mockito.never()).dispatch(
                anyLong(), anyLong(), any(), any(), anyInt(), any(Runnable.class));
    }

    @Test
    void non_regular_source_is_rejected_with_the_existing_read_error() throws Exception {
        Path directory = Files.createDirectory(tempDir.resolve("source-dir"));
        when(fileMapper.selectById(FILE_ID)).thenReturn(file(directory, "md"));

        performValidPreview()
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.msg").value("The source document cannot be read"));

        verify(dispatcher, org.mockito.Mockito.never()).dispatch(
                anyLong(), anyLong(), any(), any(), anyInt(), any(Runnable.class));
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
    void invalid_general_delimiter_returns_a_stable_field_error_before_dispatch() throws Exception {
        mockMvc.perform(post("/knowledge/{knowledgeId}/files/{fileId}/chunk-preview",
                        KNOWLEDGE_ID, FILE_ID)
                        .contentType("application/json")
                        .content("""
                                {"strategyCode":"GENERAL",
                                 "strategyConfig":{"delimiter":"","delimiterMode":"LITERAL","maxCharacters":500,
                                   "collapseWhitespace":true,"removeUrls":false,"removeEmails":false},
                                 "contextConfig":{"enabled":true,"limit":40},
                                 "replaceEditedDrafts":false,"lockVersion":0}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data.code").value("INVALID_STRATEGY_CONFIG"))
                .andExpect(jsonPath("$.data.fieldErrors.delimiter").exists());

        verify(dispatcher, org.mockito.Mockito.never()).dispatch(
                anyLong(), anyLong(), any(), any(), anyInt(), any(Runnable.class));
    }

    @Test
    void null_general_delimiter_returns_a_stable_field_error_before_dispatch() throws Exception {
        mockMvc.perform(post("/knowledge/{knowledgeId}/files/{fileId}/chunk-preview",
                        KNOWLEDGE_ID, FILE_ID)
                        .contentType("application/json")
                        .content("""
                                {"strategyCode":"GENERAL",
                                 "strategyConfig":{"delimiter":null,"delimiterMode":"LITERAL","maxCharacters":500,
                                   "collapseWhitespace":true,"removeUrls":false,"removeEmails":false},
                                 "contextConfig":{"enabled":true,"limit":40},
                                 "replaceEditedDrafts":false,"lockVersion":0}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data.code").value("INVALID_STRATEGY_CONFIG"))
                .andExpect(jsonPath("$.data.fieldErrors.delimiter").exists());

        verify(dispatcher, org.mockito.Mockito.never()).dispatch(
                anyLong(), anyLong(), any(), any(), anyInt(), any(Runnable.class));
    }

    @Test
    void general_request_decodes_lf_once_and_dispatches_only_typed_validated_config() throws Exception {
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(5).run();
            return null;
        }).when(dispatcher).dispatch(eq(KNOWLEDGE_ID), eq(FILE_ID), eq(PipelineState.UPLOADED),
                eq(PipelineState.CHUNKING), eq(0), any(Runnable.class));

        mockMvc.perform(post("/knowledge/{knowledgeId}/files/{fileId}/chunk-preview",
                        KNOWLEDGE_ID, FILE_ID)
                        .contentType("application/json")
                        .content("""
                                {"strategyCode":"GENERAL",
                                 "strategyConfig":{"delimiter":"\\n","delimiterMode":"LITERAL","maxCharacters":500,
                                   "collapseWhitespace":true,"removeUrls":false,"removeEmails":false},
                                 "contextConfig":{"enabled":true,"limit":40},
                                 "replaceEditedDrafts":false,"lockVersion":0}
                                """))
                .andExpect(status().isAccepted());

        var captor = org.mockito.ArgumentCaptor.forClass(ChunkPreviewWorker.Job.class);
        verify(worker).generate(captor.capture());
        GeneralChunkConfig config = (GeneralChunkConfig) captor.getValue().strategyConfig();
        assertEquals("\n", config.delimiter());
        assertEquals(OverlapUnit.CHARACTERS, captor.getValue().contextConfig().unit());
        assertEquals("general-deterministic-v1", captor.getValue().plannerVersion());
    }

    @Test
    void processing_returns_only_the_persisted_preview_summary() throws Exception {
        FileProcessing processing = processing(1L, KNOWLEDGE_ID);
        processing.setPreviewSummary(java.util.Map.of(
                "preprocessingSummary", java.util.Map.of("urlMatches", 2, "emailMatches", 1),
                "delimiterMatched", false,
                "forcedSplitCount", 3,
                "tokenLimitedSplitCount", 4));
        when(processingMapper.selectById(FILE_ID)).thenReturn(processing);

        mockMvc.perform(get("/knowledge/{knowledgeId}/files/{fileId}/processing",
                        KNOWLEDGE_ID, FILE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preprocessingSummary.urlMatches").value(2))
                .andExpect(jsonPath("$.preprocessingSummary.emailMatches").value(1))
                .andExpect(jsonPath("$.delimiterMatched").value(false))
                .andExpect(jsonPath("$.forcedSplitCount").value(3))
                .andExpect(jsonPath("$.tokenLimitedSplitCount").value(4));
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

    @Test
    void preview_job_carries_the_exact_confirmed_existing_chunk_snapshot() throws Exception {
        DocumentChunk edited = new DocumentChunk();
        edited.setId(91L);
        edited.setPublicId(UUID.fromString("10000000-0000-0000-0000-000000000091"));
        edited.setTenantId(1L);
        edited.setKnowledgeId(KNOWLEDGE_ID);
        edited.setFileId(FILE_ID);
        edited.setPosition(4);
        edited.setStatus(0);
        edited.setIsModified(true);
        edited.setContentHash("confirmed-hash");
        edited.setUpdateTime(OffsetDateTime.parse("2026-09-01T10:00:00+08:00"));
        when(chunkMapper.findByFile(FILE_ID, 1L, KNOWLEDGE_ID)).thenReturn(List.of(edited));
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(5).run();
            return null;
        }).when(dispatcher).dispatch(eq(KNOWLEDGE_ID), eq(FILE_ID), eq(PipelineState.UPLOADED),
                eq(PipelineState.CHUNKING), eq(0), any(Runnable.class));

        mockMvc.perform(post("/knowledge/{knowledgeId}/files/{fileId}/chunk-preview",
                        KNOWLEDGE_ID, FILE_ID)
                        .contentType("application/json")
                        .content("""
                                {"strategyCode":"MARKDOWN_OPTIMIZED",
                                 "strategyConfig":{"minTokens":100,"targetTokens":400,"maxTokens":512},
                                 "replaceEditedDrafts":true,"lockVersion":0}
                                """))
                .andExpect(status().isAccepted());

        var job = org.mockito.ArgumentCaptor.forClass(ChunkPreviewWorker.Job.class);
        verify(worker).generate(job.capture());
        assertEquals(List.of(ChunkPreviewWorker.ExistingChunkSnapshot.from(edited)),
                job.getValue().existingChunks());
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
