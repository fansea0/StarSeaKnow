package com.starsea.ai.chunking.api;

import com.starsea.ai.chunking.api.ChunkingApiModels.ChunkResponse;
import com.starsea.ai.chunking.api.ChunkingApiModels.EditChunkRequest;
import com.starsea.ai.chunking.model.OverlapUnit;
import com.starsea.ai.chunking.preview.ChunkCommandService;
import com.starsea.ai.chunking.preview.ChunkPreviewService;
import com.starsea.ai.config.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChunkVisibilityTest {

    @Test
    void chunk_listing_serializes_settings_and_read_only_overlap_without_internal_ids() throws Exception {
        ChunkPreviewService previewService = mock(ChunkPreviewService.class);
        ChunkCommandService commandService = mock(ChunkCommandService.class);
        UUID publicId = UUID.fromString("10000000-0000-0000-0000-000000000021");
        when(commandService.list(10L, 20L)).thenReturn(List.of(new ChunkResponse(
                publicId, 3, "Visible body", List.of("Guide", "Install"),
                Map.of("startLine", 7, "endLine", 11), 4, 0, true, 2,
                true, 40, OverlapUnit.TOKENS, "Previous sentence.", 5, 18,
                "CONFIGURED_LIMIT", null, "TOKENS", 4, 9, 5,
                Map.of("delimiterBefore", "---"))));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new ChunkingController(previewService, commandService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/knowledge/{knowledgeId}/files/{fileId}/chunks", 10L, 20L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].publicId").value(publicId.toString()))
                .andExpect(jsonPath("$[0].position").value(3))
                .andExpect(jsonPath("$[0].content").value("Visible body"))
                .andExpect(jsonPath("$[0].sectionPath[1]").value("Install"))
                .andExpect(jsonPath("$[0].sourceLocator.startLine").value(7))
                .andExpect(jsonPath("$[0].tokenCount").value(4))
                .andExpect(jsonPath("$[0].status").value(0))
                .andExpect(jsonPath("$[0].isModified").value(true))
                .andExpect(jsonPath("$[0].lockVersion").value(2))
                .andExpect(jsonPath("$[0].overlapEnabled").value(true))
                .andExpect(jsonPath("$[0].overlapLimit").value(40))
                .andExpect(jsonPath("$[0].overlapUnit").value("TOKENS"))
                .andExpect(jsonPath("$[0].overlapContent").value("Previous sentence."))
                .andExpect(jsonPath("$[0].overlapTokenCount").value(5))
                .andExpect(jsonPath("$[0].overlapCharacterCount").value(18))
                .andExpect(jsonPath("$[0].overlapReductionReason").value("CONFIGURED_LIMIT"))
                .andExpect(jsonPath("$[0].overlapActualLength").value(5))
                .andExpect(jsonPath("$[0].lengthUnit").value("TOKENS"))
                .andExpect(jsonPath("$[0].bodyLength").value(4))
                .andExpect(jsonPath("$[0].indexLength").value(9))
                .andExpect(jsonPath("$[0].overlapTokenLimit").doesNotExist())
                .andExpect(jsonPath("$[0].overlapUnavailableReason").doesNotExist())
                .andExpect(jsonPath("$[0].indexContent").doesNotExist())
                .andExpect(jsonPath("$[0].overlapSourceChunkId").doesNotExist())
                .andExpect(jsonPath("$[0].boundaryReason.delimiterBefore").value("---"))
                .andExpect(jsonPath("$[0].id").doesNotExist())
                .andExpect(jsonPath("$[0].tenantId").doesNotExist())
                .andExpect(jsonPath("$[0].knowledgeId").doesNotExist())
                .andExpect(jsonPath("$[0].fileId").doesNotExist());
    }

    @Test
    void patch_and_delete_routes_forward_the_stable_public_id_and_lock_version() throws Exception {
        ChunkPreviewService previewService = mock(ChunkPreviewService.class);
        ChunkCommandService commandService = mock(ChunkCommandService.class);
        UUID publicId = UUID.fromString("10000000-0000-0000-0000-000000000021");
        ChunkResponse edited = new ChunkResponse(publicId, 0, "Edited", List.of(), Map.of(),
                2, 0, true, 5);
        when(commandService.edit(10L, 20L, publicId,
                new EditChunkRequest("Edited", true, 64, 4)))
                .thenReturn(edited);
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new ChunkingController(previewService, commandService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(patch("/knowledge/{knowledgeId}/files/{fileId}/chunks/{chunkPublicId}",
                        10L, 20L, publicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Edited\",\"overlapEnabled\":true,"
                                + "\"overlapTokenLimit\":64,\"lockVersion\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lockVersion").value(5));
        mockMvc.perform(delete("/knowledge/{knowledgeId}/files/{fileId}/chunks/{chunkPublicId}",
                        10L, 20L, publicId).param("lockVersion", "5"))
                .andExpect(status().isNoContent());

        verify(commandService).edit(10L, 20L, publicId,
                new EditChunkRequest("Edited", true, 64, 4));
        verify(commandService).delete(10L, 20L, publicId, 5);
    }

    @Test
    void token_budget_error_exposes_separate_counts_in_the_422_response() throws Exception {
        ChunkPreviewService previewService = mock(ChunkPreviewService.class);
        ChunkCommandService commandService = mock(ChunkCommandService.class);
        UUID publicId = UUID.fromString("10000000-0000-0000-0000-000000000021");
        when(commandService.edit(eq(10L), eq(20L), eq(publicId),
                eq(new EditChunkRequest("Too long", false, 40, 4))))
                .thenThrow(ChunkingException.unprocessable("Edited chunk exceeds the token budget", Map.of(
                        "titleTokenCount", 10, "bodyTokenCount", 510,
                        "totalTokenCount", 520, "maxTokens", 512)));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new ChunkingController(previewService, commandService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(patch("/knowledge/{knowledgeId}/files/{fileId}/chunks/{chunkPublicId}",
                        10L, 20L, publicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Too long\",\"overlapEnabled\":false,"
                                + "\"overlapTokenLimit\":40,\"lockVersion\":4}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data.titleTokenCount").value(10))
                .andExpect(jsonPath("$.data.bodyTokenCount").value(510))
                .andExpect(jsonPath("$.data.totalTokenCount").value(520))
                .andExpect(jsonPath("$.data.maxTokens").value(512));
    }

    @Test
    void delete_transport_binding_errors_are_actual_http_400_responses() throws Exception {
        ChunkPreviewService previewService = mock(ChunkPreviewService.class);
        ChunkCommandService commandService = mock(ChunkCommandService.class);
        UUID publicId = UUID.fromString("10000000-0000-0000-0000-000000000021");
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new ChunkingController(previewService, commandService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(delete("/knowledge/{knowledgeId}/files/{fileId}/chunks/{chunkPublicId}",
                        10L, 20L, publicId))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/knowledge/{knowledgeId}/files/{fileId}/chunks/{chunkPublicId}",
                        10L, 20L, "not-a-uuid").param("lockVersion", "5"))
                .andExpect(status().isBadRequest());

        verify(commandService, org.mockito.Mockito.never())
                .delete(org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyInt());
    }
}
