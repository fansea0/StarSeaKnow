package com.fansea.ai.openapi.retrieval;

import com.fansea.ai.auth.AuthContext;
import com.fansea.ai.openapi.credential.ApiCredentialResolver;
import com.fansea.ai.openapi.credential.CredentialType;
import com.fansea.ai.openapi.credential.RagKnowledgeScopeSnapshot;
import com.fansea.ai.openapi.error.ExternalApiExceptionHandler;
import com.fansea.ai.openapi.error.ExternalApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ExternalRetrievalController.class)
@Import({ExternalRetrievalController.class, RetrievalRequestParser.class, ExternalApiExceptionHandler.class})
class ExternalRetrievalControllerTest {

    private static final String PATH = "/openapi/v1/retrieval";
    private static final UUID DOCUMENT_ID = UUID.fromString("65c28997-ad56-4812-8a50-e00e804b45cc");
    private static final UUID CHUNK_ID = UUID.fromString("a3c05b1e-c40b-4eb6-8a81-26f9874f6f7b");

    @SpringBootConfiguration
    static class TestApplication {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RetrievalDeadlineExecutor retrievalExecutor;

    @MockBean
    private CredentialRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        AuthContext.set(external(CredentialType.RAG_RETRIEVAL, Set.of(11L, 12L)));
        when(rateLimiter.acquire(41L, 60, 10, 5)).thenReturn(lease(60, 9, 1_787_890_000L));
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    void returnsStableRecordsHeadersAndCompleteSourceMetadata() throws Exception {
        when(retrievalExecutor.retrieve(any(), any())).thenReturn(List.of(new RetrievedChunk(
                "退款申请需要订单号。", 0.92, "售后服务说明.pdf", DOCUMENT_ID, CHUNK_ID, "pdf", 3, 12)));

        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-ID", "req-safe-01")
                        .content("""
                                {"query":"退款材料","retrieval_setting":{"top_k":5,"score_threshold":0.5}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].content").value("退款申请需要订单号。"))
                .andExpect(jsonPath("$.records[0].score").value(0.92))
                .andExpect(jsonPath("$.records[0].title").value("售后服务说明.pdf"))
                .andExpect(jsonPath("$.records[0].metadata.document_id").value(DOCUMENT_ID.toString()))
                .andExpect(jsonPath("$.records[0].metadata.chunk_id").value(CHUNK_ID.toString()))
                .andExpect(jsonPath("$.records[0].metadata.file_type").value("pdf"))
                .andExpect(jsonPath("$.records[0].metadata.page_number").value(3))
                .andExpect(jsonPath("$.records[0].metadata.chunk_index").value(12))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("X-Request-ID", "req-safe-01"))
                .andExpect(header().string("X-RateLimit-Limit", "60"))
                .andExpect(header().string("X-RateLimit-Remaining", "9"))
                .andExpect(header().string("X-RateLimit-Reset", "1787890000"));

        ArgumentCaptor<RetrievalQuery> query = ArgumentCaptor.forClass(RetrievalQuery.class);
        verify(retrievalExecutor).retrieve(query.capture(), any());
        assertThat(query.getValue()).isEqualTo(new RetrievalQuery("退款材料", Set.of(11L, 12L), 5, 0.5));
    }

    @Test
    void appliesDocumentedDefaults() throws Exception {
        when(retrievalExecutor.retrieve(any(), any())).thenReturn(List.of());

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"refund\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<RetrievalQuery> query = ArgumentCaptor.forClass(RetrievalQuery.class);
        verify(retrievalExecutor).retrieve(query.capture(), any());
        assertThat(query.getValue().topK()).isEqualTo(5);
        assertThat(query.getValue().scoreThreshold()).isZero();
    }

    @Test
    void rejectsKnowledgeIdField() throws Exception {
        assertInvalidRequest("{\"query\":\"refund\",\"knowledge_id\":11}", "knowledge_id");
    }

    @Test
    void rejectsMetadataConditionField() throws Exception {
        assertInvalidRequest("{\"query\":\"refund\",\"metadata_condition\":{}}", "metadata_condition");
    }

    @Test
    void rejectsArbitraryUnknownField() throws Exception {
        assertInvalidRequest("{\"query\":\"refund\",\"debug\":true}", "debug");
    }

    @Test
    void rejectsUnknownRetrievalSettingField() throws Exception {
        assertInvalidRequest("{\"query\":\"refund\",\"retrieval_setting\":{\"top_k\":5,\"filter\":{}}}",
                "retrieval_setting.filter");
    }

    @Test
    void rejectsMalformedUtf8Bytes() throws Exception {
        byte[] prefix = "{\"query\":\"".getBytes(StandardCharsets.UTF_8);
        byte[] suffix = "\"}".getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[prefix.length + 2 + suffix.length];
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        body[prefix.length] = (byte) 0xC3;
        body[prefix.length + 1] = 0x28;
        System.arraycopy(suffix, 0, body, prefix.length + 2, suffix.length);

        assertInvalidWireJson(body);
    }

    @Test
    void rejectsUtf16JsonRegardlessOfByteOrder() throws Exception {
        String json = "{\"query\":\"refund\"}";

        assertInvalidWireJson(json.getBytes(StandardCharsets.UTF_16BE));
        assertInvalidWireJson(json.getBytes(StandardCharsets.UTF_16LE));
    }

    @Test
    void rejectsUtf32JsonRegardlessOfByteOrder() throws Exception {
        String json = "{\"query\":\"refund\"}";

        assertInvalidWireJson(json.getBytes(Charset.forName("UTF-32BE")));
        assertInvalidWireJson(json.getBytes(Charset.forName("UTF-32LE")));
    }

    @Test
    void rejectsSecondJsonDocumentAfterRequestObject() throws Exception {
        assertInvalidWireJson("{\"query\":\"first\"} {\"query\":\"second\"}"
                .getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsTrailingJsonTokenAfterRequestObject() throws Exception {
        assertInvalidWireJson("{\"query\":\"first\"} true".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsDuplicateJsonKeys() throws Exception {
        assertInvalidWireJson("{\"query\":\"first\",\"query\":\"second\"}"
                .getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsTextPlainWithStableExternalEnvelope() throws Exception {
        assertUnsupportedMediaType(MediaType.TEXT_PLAIN);
    }

    @Test
    void rejectsOctetStreamWithStableExternalEnvelope() throws Exception {
        assertUnsupportedMediaType(MediaType.APPLICATION_OCTET_STREAM);
    }

    @Test
    void rejectsMissingContentTypeWithStableExternalEnvelope() throws Exception {
        mockMvc.perform(post(PATH).content("{\"query\":\"refund\"}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("invalid_request"))
                .andExpect(jsonPath("$.error.param").value("Content-Type"))
                .andExpect(header().exists("X-Request-ID"))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("HttpMediaType"))));
        verify(retrievalExecutor, never()).retrieve(any(), any());
    }

    @ParameterizedTest(name = "rejects nonempty Content-Type {0}")
    @ValueSource(strings = {"*/*", "application/*", "application/*+json", "not a media type"})
    void rejectsWildcardAndMalformedContentTypesWithNonemptyBody(String contentType) throws Exception {
        assertUnsupportedRawContentType(contentType, "{\"query\":\"refund\"}", "req-raw-nonempty");
    }

    @ParameterizedTest(name = "rejects empty Content-Type {0}")
    @ValueSource(strings = {"*/*", "application/*", "application/*+json", "not a media type"})
    void rejectsWildcardAndMalformedContentTypesWithEmptyBody(String contentType) throws Exception {
        assertUnsupportedRawContentType(contentType, "", "req-raw-empty");
    }

    @Test
    void acceptsCaseInsensitiveApplicationJsonWithCharsetParameter() throws Exception {
        when(retrievalExecutor.retrieve(any(), any())).thenReturn(List.of());

        mockMvc.perform(post(PATH)
                        .header(HttpHeaders.CONTENT_TYPE, "Application/JSON; Charset=UTF-8")
                        .content("{\"query\":\"refund\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"records\":[]}"));

        verify(retrievalExecutor).retrieve(any(), any());
    }

    @Test
    void rejectsEmptyApplicationJsonBodyWithoutAmbiguousRouting() throws Exception {
        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-ID", "req-empty-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_request"))
                .andExpect(header().string("X-Request-ID", "req-empty-json"))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Ambiguous"))));
        verify(retrievalExecutor, never()).retrieve(any(), any());
    }

    @Test
    void rejectsEmptyBodyWithoutContentTypeWithoutAmbiguousRouting() throws Exception {
        assertUnsupportedEmptyRequest(null, "req-empty-missing");
    }

    @Test
    void rejectsEmptyTextPlainBodyWithoutAmbiguousRouting() throws Exception {
        assertUnsupportedEmptyRequest(MediaType.TEXT_PLAIN, "req-empty-text");
    }

    @Test
    void rejectsEmptyOctetStreamBodyWithoutAmbiguousRouting() throws Exception {
        assertUnsupportedEmptyRequest(MediaType.APPLICATION_OCTET_STREAM, "req-empty-octet");
    }

    @Test
    void rejectsBodyLargerThanThirtyTwoKiBBeforeJsonParsing() throws Exception {
        byte[] body = ("{\"query\":\"" + "x".repeat(33 * 1024) + "\"}").getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("request_too_large"))
                .andExpect(header().exists("X-Request-ID"));

        verifyNoInteractions(retrievalExecutor);
    }

    @Test
    void rejectsBlankAndOverTwoHundredFiftyCharacterQueries() throws Exception {
        assertInvalidRequest("{\"query\":\"   \"}", "query");
        assertInvalidRequest("{\"query\":\"" + "q".repeat(251) + "\"}", "query");
    }

    @Test
    void rejectsIdeographicSpaceAndNonBreakingSpaceOnlyQueries() throws Exception {
        assertInvalidRequest("{\"query\":\"\\u3000\\u3000\"}", "query");
        assertInvalidRequest("{\"query\":\"\\u00a0\\u00a0\"}", "query");
    }

    @Test
    void trimsUnicodeBoundarySpacesWithoutChangingCjkQuery() throws Exception {
        when(retrievalExecutor.retrieve(any(), any())).thenReturn(List.of());

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"\\u3000退款材料\\u00a0\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<RetrievalQuery> query = ArgumentCaptor.forClass(RetrievalQuery.class);
        verify(retrievalExecutor).retrieve(query.capture(), any());
        assertThat(query.getValue().query()).isEqualTo("退款材料");
    }

    @Test
    void rejectsTopKOutsideOneThroughTwenty() throws Exception {
        assertInvalidRequest("{\"query\":\"refund\",\"retrieval_setting\":{\"top_k\":0}}",
                "retrieval_setting.top_k");
        assertInvalidRequest("{\"query\":\"refund\",\"retrieval_setting\":{\"top_k\":21}}",
                "retrieval_setting.top_k");
    }

    @Test
    void rejectsScoreThresholdOutsideZeroThroughOne() throws Exception {
        assertInvalidRequest("{\"query\":\"refund\",\"retrieval_setting\":{\"score_threshold\":-0.01}}",
                "retrieval_setting.score_threshold");
        assertInvalidRequest("{\"query\":\"refund\",\"retrieval_setting\":{\"score_threshold\":1.01}}",
                "retrieval_setting.score_threshold");
    }

    @Test
    void rejectsEmptyScopeBeforeRetrieval() throws Exception {
        AuthContext.set(external(CredentialType.RAG_RETRIEVAL, Set.of()));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"refund\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("credential_has_no_knowledge_scope"))
                .andExpect(header().exists("X-Request-ID"));

        verifyNoInteractions(retrievalExecutor, rateLimiter);
    }

    @Test
    void rejectsAgentCredentialTypeBeforeRetrieval() throws Exception {
        AuthContext.set(external(CredentialType.AGENT_INVOKE, Set.of(11L)));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"refund\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("credential_type_not_allowed"));

        verifyNoInteractions(retrievalExecutor, rateLimiter);
    }

    @Test
    void rejectsJwtAuthContext() throws Exception {
        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 9L, 7L, "tenant_admin", "jti"));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"refund\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("authentication_failed"));

        verifyNoInteractions(retrievalExecutor, rateLimiter);
    }

    @Test
    void returnsExactEmptyRecordsResponse() throws Exception {
        when(retrievalExecutor.retrieve(any(), any())).thenReturn(List.of());

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"refund\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"records\":[]}", true))
                .andExpect(content().string("{\"records\":[]}"));
    }

    @Test
    void returnsStableRateLimitEnvelopeAndRetryHeader() throws Exception {
        when(rateLimiter.acquire(41L, 60, 10, 5)).thenThrow(
                new CredentialRateLimiter.RateLimitExceededException(60, 0, 1_787_890_001L, 2));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"refund\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "2"))
                .andExpect(header().exists("X-Request-ID"))
                .andExpect(jsonPath("$.error.code").value("rate_limit_exceeded"))
                .andExpect(jsonPath("$.error.message").value("Rate limit exceeded."));

        verifyNoInteractions(retrievalExecutor);
    }

    @Test
    void closesRateLimitLeaseWhenRetrievalFails() throws Exception {
        TrackingLease lease = new TrackingLease();
        when(rateLimiter.acquire(41L, 60, 10, 5)).thenReturn(lease);
        when(retrievalExecutor.retrieve(any(), any())).thenThrow(new IllegalStateException("sensitive database details"));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"refund\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("internal_error"))
                .andExpect(jsonPath("$.error.message").value("Internal server error."))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("sensitive"))));

        assertThat(lease.closed).isTrue();
    }

    @Test
    void sanitizesAuthShapedExternalExceptionThrownByRetrievalService() throws Exception {
        when(retrievalExecutor.retrieve(any(), any())).thenThrow(new ExternalApiException(
                HttpStatus.UNAUTHORIZED, "authentication_failed", "downstream secret details"));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"refund\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("internal_error"))
                .andExpect(jsonPath("$.error.message").value("Internal server error."))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("downstream secret"))));
    }

    @Test
    void mapsDependencyFailureAndTimeoutWithoutLeakingInternals() throws Exception {
        when(retrievalExecutor.retrieve(any(), any())).thenThrow(new DataAccessResourceFailureException("database host secret"));
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"refund\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("retrieval_unavailable"))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("database host"))));

        reset(retrievalExecutor);
        TrackingLease timeoutLease = new TrackingLease();
        when(rateLimiter.acquire(41L, 60, 10, 5)).thenReturn(timeoutLease);
        when(retrievalExecutor.retrieve(any(), any())).thenThrow(new TimeoutException("slow query"));
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"refund\"}"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.error.code").value("retrieval_timeout"))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("slow query"))));
        assertThat(timeoutLease.closed).isTrue();
    }

    @Test
    void mapsSaturatedDeadlineExecutorToUnavailableAndReleasesLease() throws Exception {
        TrackingLease lease = new TrackingLease();
        when(rateLimiter.acquire(41L, 60, 10, 5)).thenReturn(lease);
        when(retrievalExecutor.retrieve(any(), any())).thenThrow(new RejectedExecutionException("queue detail"));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"refund\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("retrieval_unavailable"))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("queue detail"))));
        assertThat(lease.closed).isTrue();
    }

    @Test
    void truncatesChunkContentAndDropsLowestScoresToBoundResponse() throws Exception {
        List<RetrievedChunk> chunks = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            chunks.add(new RetrievedChunk("界".repeat(3_000), 1.0 - index * 0.01, "source-" + index,
                    UUID.randomUUID(), UUID.randomUUID(), "txt", null, index));
        }
        when(retrievalExecutor.retrieve(any(), any())).thenReturn(chunks);

        byte[] response = mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"refund\",\"retrieval_setting\":{\"top_k\":20}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].score").value(1.0))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(response.length).isLessThanOrEqualTo(128 * 1024);
        String responseJson = new String(response, StandardCharsets.UTF_8);
        assertThat(responseJson).contains("source-0").doesNotContain("source-19");
    }

    @Test
    void normalizesScoresBeforeSortingPublicRecordsDescending() throws Exception {
        when(retrievalExecutor.retrieve(any(), any())).thenReturn(List.of(
                new RetrievedChunk("invalid", Double.NaN, "invalid-source", DOCUMENT_ID, CHUNK_ID,
                        "txt", null, 0),
                new RetrievedChunk("valid", 0.8, "valid-source", DOCUMENT_ID, CHUNK_ID,
                        "txt", null, 1)));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"refund\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].content").value("valid"))
                .andExpect(jsonPath("$.records[0].score").value(0.8))
                .andExpect(jsonPath("$.records[1].score").value(0.0));
    }

    private void assertInvalidRequest(String body, String param) throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_request"))
                .andExpect(jsonPath("$.error.param").value(param))
                .andExpect(header().exists("X-Request-ID"));
        verify(retrievalExecutor, never()).retrieve(any(), any());
    }

    private void assertInvalidWireJson(byte[] body) throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_request"))
                .andExpect(header().exists("X-Request-ID"))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Json"))));
        verify(retrievalExecutor, never()).retrieve(any(), any());
    }

    private void assertUnsupportedMediaType(MediaType mediaType) throws Exception {
        mockMvc.perform(post(PATH).contentType(mediaType).content("{\"query\":\"refund\"}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("invalid_request"))
                .andExpect(jsonPath("$.error.param").value("Content-Type"))
                .andExpect(header().exists("X-Request-ID"))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("HttpMediaType"))));
        verify(retrievalExecutor, never()).retrieve(any(), any());
    }

    private void assertUnsupportedEmptyRequest(MediaType mediaType, String requestId) throws Exception {
        var request = post(PATH).header("X-Request-ID", requestId);
        if (mediaType != null) {
            request.contentType(mediaType);
        }
        mockMvc.perform(request)
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("invalid_request"))
                .andExpect(jsonPath("$.error.param").value("Content-Type"))
                .andExpect(header().string("X-Request-ID", requestId))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Ambiguous"))));
        verify(retrievalExecutor, never()).retrieve(any(), any());
    }

    private void assertUnsupportedRawContentType(String contentType, String body, String requestId) throws Exception {
        mockMvc.perform(post(PATH)
                        .header(HttpHeaders.CONTENT_TYPE, contentType)
                        .header("X-Request-ID", requestId)
                        .content(body))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.request_id").value(requestId))
                .andExpect(jsonPath("$.error.code").value("invalid_request"))
                .andExpect(jsonPath("$.error.param").value("Content-Type"))
                .andExpect(header().string("X-Request-ID", requestId))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("MediaType"))));
        verify(retrievalExecutor, never()).retrieve(any(), any());
    }

    private static AuthContext external(CredentialType type, Set<Long> knowledgeIds) {
        ApiCredentialResolver.ResolvedCredential credential = new ApiCredentialResolver.ResolvedCredential(
                41L, 7L, type, "test", "active", OffsetDateTime.now().plusDays(1), List.of(),
                60, 10, 5, 3L, new RagKnowledgeScopeSnapshot(knowledgeIds));
        return AuthContext.external(credential);
    }

    private static CredentialRateLimiter.RateLimitLease lease(int limit, int remaining, long resetEpochSecond) {
        return new CredentialRateLimiter.RateLimitLease() {
            @Override public int limit() { return limit; }
            @Override public int remaining() { return remaining; }
            @Override public long resetEpochSecond() { return resetEpochSecond; }
            @Override public void close() { }
        };
    }

    private static final class TrackingLease implements CredentialRateLimiter.RateLimitLease {
        private boolean closed;
        @Override public int limit() { return 60; }
        @Override public int remaining() { return 9; }
        @Override public long resetEpochSecond() { return 1_787_890_000L; }
        @Override public void close() { closed = true; }
    }
}
