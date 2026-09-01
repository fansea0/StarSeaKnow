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
import static org.junit.jupiter.api.Assertions.assertNull;
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
        ExternalRetrievalController.RetrievalResponse payload = retrieve(
                List.of(retrievedChunkWithSources()));

        ExternalRetrievalController.RetrievalRecord record = payload.records().get(0);
        assertPublicMetadataKeys(record.metadata());
        assertEquals(DOCUMENT_ID.toString(), record.metadata().get("document_id"));
        assertEquals(CHUNK_ID.toString(), record.metadata().get("chunk_id"));
        assertEquals("md", record.metadata().get("file_type"));
        assertEquals(3, record.metadata().get("chunk_index"));
        assertEquals(List.of("Guide", "Details"), record.metadata().get("section_path"));
        assertEquals(7, record.metadata().get("start_line"));
        assertEquals(11, record.metadata().get("end_line"));
    }

    @Test
    void line_bounds_accept_only_positive_integral_numbers_in_integer_range() throws Exception {
        ExternalRetrievalController.RetrievalResponse payload = retrieve(List.of(
                retrieved("valid", Map.of("startLine", Integer.valueOf(7), "endLine", Long.valueOf(11))),
                retrieved("objects", Map.of("startLine", "7", "endLine", Map.of("line", 11))),
                retrieved("fractional", Map.of("startLine", List.of(7), "endLine", 11.5d)),
                retrieved("range", Map.of("startLine", -1L, "endLine", Long.MAX_VALUE))));

        assertEquals(4, payload.records().size());
        for (ExternalRetrievalController.RetrievalRecord record : payload.records()) {
            assertPublicMetadataKeys(record.metadata());
        }
        assertEquals(7, payload.records().get(0).metadata().get("start_line"));
        assertEquals(11, payload.records().get(0).metadata().get("end_line"));
        for (int index = 1; index < payload.records().size(); index++) {
            assertNull(payload.records().get(index).metadata().get("start_line"));
            assertNull(payload.records().get(index).metadata().get("end_line"));
        }
    }

    @Test
    void omits_retrieved_chunks_with_null_empty_or_blank_content() throws Exception {
        ExternalRetrievalController.RetrievalResponse payload = retrieve(List.of(
                retrieved(null, Map.of("startLine", 1, "endLine", 1)),
                retrieved("", Map.of("startLine", 1, "endLine", 1)),
                retrieved(" \n\t", Map.of("startLine", 1, "endLine", 1)),
                retrieved("database content", Map.of("startLine", 1, "endLine", 1))));

        assertEquals(1, payload.records().size());
        assertEquals("database content", payload.records().get(0).content());
    }

    private ExternalRetrievalController.RetrievalResponse retrieve(List<RetrievedChunk> chunks) throws Exception {
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
        when(executor.retrieve(any(RetrievalQuery.class), eq(context), eq(lease)))
                .thenReturn(chunks);
        ExternalRetrievalController controller = new ExternalRetrievalController(
                new RetrievalRequestParser(objectMapper), executor, rateLimiter, objectMapper, registries);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent("""
                {"query":"stars","retrieval_setting":{"top_k":2,"score_threshold":0.1}}
                """.getBytes(StandardCharsets.UTF_8));

        ResponseEntity<ExternalRetrievalController.RetrievalResponse> response =
                controller.retrieve(request, new MockHttpServletResponse());
        return response.getBody();
    }

    private AuthContext externalContext() {
        return AuthContext.external(new ApiCredentialResolver.ResolvedCredential(
                42L, 1L, CredentialType.RAG_RETRIEVAL, "live", "active", null,
                List.of(), 60, 10, 5, 3L, new RagKnowledgeScopeSnapshot(Set.of(10L))));
    }

    private RetrievedChunk retrievedChunkWithSources() {
        return retrieved("database index content",
                Map.of("startLine", 7, "endLine", 11, "blockIds", List.of("internal-block-id"),
                        "chunkDbId", 123L));
    }

    private RetrievedChunk retrieved(String content, Map<String, Object> sourceLocator) {
        return new RetrievedChunk(content, 0.91, "guide.md", DOCUMENT_ID, CHUNK_ID,
                "md", null, 3, List.of("Guide", "Details"), sourceLocator);
    }

    private void assertPublicMetadataKeys(Map<String, Object> metadata) {
        assertEquals(Set.of("document_id", "chunk_id", "file_type", "chunk_index",
                "section_path", "start_line", "end_line"), metadata.keySet());
    }
}
