package com.starsea.ai.openapi.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.openapi.credential.ApiCredentialResolver;
import com.starsea.ai.openapi.credential.CredentialType;
import com.starsea.ai.openapi.credential.RagKnowledgeScopeSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("validLineNumbers")
    void normalizes_each_supported_integral_start_line_to_integer(
            String description, Object sourceValue, int expected) throws Exception {
        Map<String, Object> locator = new LinkedHashMap<>();
        locator.put("startLine", sourceValue);

        ExternalRetrievalController.RetrievalRecord record = retrieve(
                List.of(retrieved("valid " + description, locator))).records().get(0);

        assertPublicMetadataKeys(record.metadata());
        Object normalized = record.metadata().get("start_line");
        assertEquals(Integer.class, normalized.getClass());
        assertEquals(expected, normalized);
        assertNull(record.metadata().get("end_line"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidLineValues")
    void rejects_each_invalid_start_line_without_leaking_its_structure(
            String description, Object sourceValue) throws Exception {
        Map<String, Object> locator = new LinkedHashMap<>();
        locator.put("startLine", sourceValue);

        ExternalRetrievalController.RetrievalRecord record = retrieve(
                List.of(retrieved("invalid " + description, locator))).records().get(0);

        assertPublicMetadataKeys(record.metadata());
        assertTrue(record.metadata().containsKey("start_line"));
        assertNull(record.metadata().get("start_line"));
        assertNull(record.metadata().get("end_line"));
    }

    @Test
    void normalizes_end_line_independently_from_invalid_start_line() throws Exception {
        ExternalRetrievalController.RetrievalRecord record = retrieve(List.of(
                retrieved("independent end", Map.of("startLine", "not-a-number", "endLine", 12L))))
                .records().get(0);

        assertPublicMetadataKeys(record.metadata());
        assertNull(record.metadata().get("start_line"));
        assertEquals(Integer.class, record.metadata().get("end_line").getClass());
        assertEquals(12, record.metadata().get("end_line"));
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

    private static Stream<Arguments> validLineNumbers() {
        return Stream.of(
                Arguments.of("byte", Byte.valueOf((byte) 1), 1),
                Arguments.of("short", Short.valueOf((short) 2), 2),
                Arguments.of("integer", Integer.valueOf(3), 3),
                Arguments.of("long", Long.valueOf(4L), 4),
                Arguments.of("big integer", BigInteger.valueOf(5L), 5),
                Arguments.of("exact big decimal", new BigDecimal("6.000"), 6),
                Arguments.of("exact float", Float.valueOf(7.0f), 7),
                Arguments.of("exact double", Double.valueOf(8.0d), 8));
    }

    private static Stream<Arguments> invalidLineValues() {
        long aboveMaximum = (long) Integer.MAX_VALUE + 1L;
        return Stream.of(
                Arguments.of("zero byte", Byte.valueOf((byte) 0)),
                Arguments.of("negative short", Short.valueOf((short) -1)),
                Arguments.of("zero integer", Integer.valueOf(0)),
                Arguments.of("negative long", Long.valueOf(-1L)),
                Arguments.of("zero big integer", BigInteger.ZERO),
                Arguments.of("negative big integer", BigInteger.valueOf(-1L)),
                Arguments.of("zero big decimal", BigDecimal.ZERO),
                Arguments.of("negative big decimal", new BigDecimal("-1")),
                Arguments.of("zero float", Float.valueOf(0.0f)),
                Arguments.of("negative double", Double.valueOf(-1.0d)),
                Arguments.of("float NaN", Float.valueOf(Float.NaN)),
                Arguments.of("float positive infinity", Float.valueOf(Float.POSITIVE_INFINITY)),
                Arguments.of("float negative infinity", Float.valueOf(Float.NEGATIVE_INFINITY)),
                Arguments.of("double NaN", Double.valueOf(Double.NaN)),
                Arguments.of("double positive infinity", Double.valueOf(Double.POSITIVE_INFINITY)),
                Arguments.of("double negative infinity", Double.valueOf(Double.NEGATIVE_INFINITY)),
                Arguments.of("fractional float", Float.valueOf(1.5f)),
                Arguments.of("fractional double", Double.valueOf(2.5d)),
                Arguments.of("fractional big decimal", new BigDecimal("3.5")),
                Arguments.of("long above integer maximum", Long.valueOf(aboveMaximum)),
                Arguments.of("big integer above integer maximum", BigInteger.valueOf(aboveMaximum)),
                Arguments.of("big decimal above integer maximum", BigDecimal.valueOf(aboveMaximum)),
                Arguments.of("double above integer maximum", Double.valueOf((double) aboveMaximum)),
                Arguments.of("numeric string", "7"),
                Arguments.of("map", Map.of("line", 7)),
                Arguments.of("list", List.of(7)),
                Arguments.of("null", (Object) null));
    }
}
