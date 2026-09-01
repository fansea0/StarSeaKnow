package com.starsea.ai.openapi.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.openapi.credential.ApiCredentialResolver;
import com.starsea.ai.openapi.credential.CredentialType;
import com.starsea.ai.openapi.credential.RagKnowledgeScopeSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExternalRetrievalControllerTest {

    private static final UUID DOCUMENT_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID CHUNK_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @AfterEach
    void clearAuth() {
        AuthContext.clear();
    }

    @Test
    void exposes_only_public_source_metadata_with_section_and_line_bounds() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        RetrievalDeadlineExecutor executor = mock(RetrievalDeadlineExecutor.class);
        CredentialRateLimiter rateLimiter = mock(CredentialRateLimiter.class);
        CredentialRateLimiter.RateLimitLease lease = mock(CredentialRateLimiter.RateLimitLease.class);
        when(lease.limit()).thenReturn(60);
        when(lease.remaining()).thenReturn(59);
        when(lease.resetEpochSecond()).thenReturn(1234L);
        when(rateLimiter.acquire(42L, 60, 10, 5)).thenReturn(lease);
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> registries = mock(ObjectProvider.class);
        AuthContext context = externalContext();
        AuthContext.set(context);
        RetrievedChunk retrieved = retrievedChunkWithSources();
        when(executor.retrieve(any(RetrievalQuery.class), eq(context), eq(lease)))
                .thenReturn(List.of(retrieved));
        ExternalRetrievalController controller = new ExternalRetrievalController(
                new RetrievalRequestParser(objectMapper), executor, rateLimiter, objectMapper, registries);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent("""
                {"query":"stars","retrieval_setting":{"top_k":2,"score_threshold":0.1}}
                """.getBytes(StandardCharsets.UTF_8));

        ResponseEntity<ExternalRetrievalController.RetrievalResponse> response =
                controller.retrieve(request, new MockHttpServletResponse());

        ExternalRetrievalController.RetrievalRecord record = response.getBody().records().get(0);
        assertEquals(Set.of("document_id", "chunk_id", "file_type", "chunk_index",
                "section_path", "start_line", "end_line"), record.metadata().keySet());
        assertEquals(DOCUMENT_ID.toString(), record.metadata().get("document_id"));
        assertEquals(CHUNK_ID.toString(), record.metadata().get("chunk_id"));
        assertEquals("md", record.metadata().get("file_type"));
        assertEquals(3, record.metadata().get("chunk_index"));
        assertEquals(List.of("Guide", "Details"), record.metadata().get("section_path"));
        assertEquals(7, record.metadata().get("start_line"));
        assertEquals(11, record.metadata().get("end_line"));
    }

    private AuthContext externalContext() {
        return AuthContext.external(new ApiCredentialResolver.ResolvedCredential(
                42L, 1L, CredentialType.RAG_RETRIEVAL, "live", "active", null,
                List.of(), 60, 10, 5, 3L, new RagKnowledgeScopeSnapshot(Set.of(10L))));
    }

    private RetrievedChunk retrievedChunkWithSources() throws Exception {
        return new RetrievedChunk(
                "database index content", 0.91, "guide.md", DOCUMENT_ID, CHUNK_ID,
                "md", null, 3, List.of("Guide", "Details"),
                Map.of("startLine", 7, "endLine", 11, "blockIds", List.of("internal-block-id"),
                        "chunkDbId", 123L));
    }
}
