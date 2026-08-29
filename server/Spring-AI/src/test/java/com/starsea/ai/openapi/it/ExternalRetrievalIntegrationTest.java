package com.starsea.ai.openapi.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.starsea.ai.auth.PasswordEncoder;
import com.starsea.ai.openapi.credential.ApiKeyCodec;
import com.starsea.ai.openapi.credential.CredentialType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.main.banner-mode=off",
        "external-api.authentication-failure-threshold=1000"
})
@ExtendWith(OutputCaptureExtension.class)
class ExternalRetrievalIntegrationTest {

    private static final String FIXTURE_PREFIX = "retrieval-it-";
    private static final String TENANT_A_CODE = FIXTURE_PREFIX + "tenant-a";
    private static final String TENANT_B_CODE = FIXTURE_PREFIX + "tenant-b";
    private static final String TENANT_A_USERNAME = FIXTURE_PREFIX + "admin-a";
    private static final String TENANT_B_USERNAME = FIXTURE_PREFIX + "admin-b";
    private static final String PASSWORD = "Strong!123";
    private static final String RETRIEVAL_BODY = """
            {"query":"test retrieval","retrieval_setting":{"top_k":3,"score_threshold":0.0}}
            """;

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ApiKeyCodec apiKeyCodec;

    @MockBean
    private VectorStore vectorStore;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private long tenantAId;
    private long tenantBId;
    private long tenantAAdminId;
    private long knowledgeA1Id;
    private long knowledgeA2Id;
    private long knowledgeB1Id;
    private long knowledgeB2Id;
    private long enabledFileA1Id;
    private long enabledFileA2Id;
    private long disabledFileAId;
    private long enabledFileBId;
    private UUID knowledgeA1PublicId;
    private UUID knowledgeA2PublicId;
    private String tenantAdminJwt;

    @BeforeEach
    void setUp() throws Exception {
        cleanFixtures();
        reset(vectorStore);
        insertFixtures();
        tenantAdminJwt = login(TENANT_A_USERNAME);
    }

    @AfterEach
    void tearDown() {
        cleanFixtures();
    }

    @Test
    void managementCreateRetrievalAndRevokeEnforceIsolationAndImmediateInvalidation(CapturedOutput output)
            throws Exception {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(vectorDocument()));
        CreatedCredential created = createCredential(
                "two-knowledge-flow", Set.of(knowledgeA1PublicId, knowledgeA2PublicId), List.of(),
                60, 10, 5, null);

        HttpResponse retrieval = postRetrieval(created.apiKey(), RETRIEVAL_BODY);

        assertThat(retrieval.status()).isEqualTo(200);
        assertThat(retrieval.body().at("/records/0/content").asText()).isEqualTo("authorized result");
        assertThat(retrieval.body().at("/records/0/metadata/document_id").asText())
                .isEqualTo(filePublicId(enabledFileA1Id).toString());
        ArgumentCaptor<SearchRequest> searchRequest = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore, times(1)).similaritySearch(searchRequest.capture());
        SearchRequest captured = searchRequest.getValue();
        assertThat(captured.getQuery()).isEqualTo("test retrieval");
        assertThat(captured.getTopK()).isEqualTo(3);
        assertThat(captured.getSimilarityThreshold()).isZero();
        assertScopedFilter(captured.getFilterExpression());

        String digest = jdbc.queryForObject(
                "SELECT secret_digest FROM api_credential WHERE public_id = ?", String.class, created.id());
        assertThat(digest).matches("[0-9a-f]{64}").isNotEqualTo(created.apiKey());
        HttpResponse list = request("/tenant/api-credentials", HttpMethod.GET, null, tenantAdminJwt, null);
        HttpResponse detail = request("/tenant/api-credentials/" + created.id(), HttpMethod.GET,
                null, tenantAdminJwt, null);
        assertThat(list.status()).isEqualTo(200);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(list.rawBody()).doesNotContain(
                created.apiKey(), "apiKey", "secretDigest", "secret_digest", "pepperVersion");
        assertThat(detail.rawBody()).doesNotContain(
                created.apiKey(), "apiKey", "secretDigest", "secret_digest", "pepperVersion");

        HttpResponse revoke = request("/tenant/api-credentials/" + created.id() + "/revoke",
                HttpMethod.POST, null, tenantAdminJwt, null);
        assertThat(revoke.status()).isEqualTo(200);

        HttpResponse afterRevoke = postRetrieval(created.apiKey(), RETRIEVAL_BODY);
        assertThat(afterRevoke.status()).isEqualTo(401);
        assertThat(afterRevoke.body().at("/error/code").asText()).isEqualTo("authentication_failed");
        verify(vectorStore, times(1)).similaritySearch(any(SearchRequest.class));
        assertThat(output.toString()).doesNotContain(created.apiKey());
    }

    @Test
    void emptyKnowledgeScopeIsForbiddenBeforeVectorSearch() throws Exception {
        CreatedCredential emptyScope = createCredential(
                "empty-scope", Set.of(), List.of(), 60, 10, 5, null);

        HttpResponse response = postRetrieval(emptyScope.apiKey(), RETRIEVAL_BODY);

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.body().at("/error/code").asText())
                .isEqualTo("credential_has_no_knowledge_scope");
        verifyNoInteractions(vectorStore);
    }

    @Test
    void clientSelectedKnowledgeAndMetadataFieldsAreRejectedBeforeVectorSearch() throws Exception {
        CreatedCredential credential = createCredential(
                "strict-shape", Set.of(knowledgeA1PublicId), List.of(), 60, 10, 5, null);

        HttpResponse knowledgeId = postRetrieval(credential.apiKey(), """
                {"query":"x","retrieval_setting":{"top_k":3,"score_threshold":0.0},"knowledge_id":"foreign"}
                """);
        HttpResponse metadataCondition = postRetrieval(credential.apiKey(), """
                {"query":"x","retrieval_setting":{"top_k":3,"score_threshold":0.0},"metadata_condition":{}}
                """);

        assertThat(knowledgeId.status()).isEqualTo(400);
        assertThat(knowledgeId.body().at("/error/code").asText()).isEqualTo("invalid_request");
        assertThat(knowledgeId.body().at("/error/param").asText()).isEqualTo("knowledge_id");
        assertThat(metadataCondition.status()).isEqualTo(400);
        assertThat(metadataCondition.body().at("/error/code").asText()).isEqualTo("invalid_request");
        assertThat(metadataCondition.body().at("/error/param").asText()).isEqualTo("metadata_condition");
        verifyNoInteractions(vectorStore);
    }

    @Test
    void businessJwtCannotCallRetrievalAndExternalKeyCannotCallInternalRoutes() throws Exception {
        CreatedCredential credential = createCredential(
                "route-separation", Set.of(knowledgeA1PublicId), List.of(), 60, 10, 5, null);

        HttpResponse jwtOnRetrieval = postRetrieval(tenantAdminJwt, RETRIEVAL_BODY);
        HttpResponse keyOnKnowledge = request(
                "/knowledge/list", HttpMethod.GET, null, credential.apiKey(), null);
        HttpResponse keyOnCredentialManagement = request(
                "/tenant/api-credentials", HttpMethod.GET, null, credential.apiKey(), null);

        assertThat(jwtOnRetrieval.status()).isEqualTo(400);
        assertThat(jwtOnRetrieval.body().at("/error/code").asText())
                .isEqualTo("invalid_authorization_header");
        assertThat(keyOnKnowledge.status()).isEqualTo(401);
        assertThat(keyOnCredentialManagement.status()).isEqualTo(401);
        verifyNoInteractions(vectorStore);
    }

    @Test
    void disabledExpiredIpRestrictedAndInvalidTypeKeysFailBeforeVectorSearch() throws Exception {
        CreatedCredential disabled = createCredential(
                "disabled", Set.of(knowledgeA1PublicId), List.of(), 60, 10, 5, null);
        jdbc.update("UPDATE api_credential SET status = 'disabled' WHERE public_id = ?", disabled.id());
        CreatedCredential expired = createCredential(
                "expired", Set.of(knowledgeA1PublicId), List.of(), 60, 10, 5,
                Instant.now().minusSeconds(60));
        CreatedCredential wrongIp = createCredential(
                "wrong-ip", Set.of(knowledgeA1PublicId), List.of("203.0.113.0/24"),
                60, 10, 5, null);
        CreatedCredential rag = createCredential(
                "prefix-type", Set.of(knowledgeA1PublicId), List.of(), 60, 10, 5, null);
        String unknownPrefix = rag.apiKey().replaceFirst("^rag_test_", "unknown_test_");
        String mismatchedType = rag.apiKey().replaceFirst("^rag_test_", "agt_test_");
        String agentKey = insertAgentCredential();

        assertExternalFailure(disabled.apiKey(), 403, "credential_disabled");
        assertExternalFailure(expired.apiKey(), 401, "authentication_failed");
        assertExternalFailure(wrongIp.apiKey(), 403, "ip_not_allowed");
        assertExternalFailure(unknownPrefix, 401, "authentication_failed");
        assertExternalFailure(mismatchedType, 401, "authentication_failed");
        assertExternalFailure(agentKey, 401, "credential_type_not_supported");
        verifyNoInteractions(vectorStore);
    }

    @Test
    void requestsBeyondCredentialBurstReturn429WithRetryHeaders() throws Exception {
        CreatedCredential credential = createCredential(
                "rate-limited", Set.of(knowledgeA1PublicId), List.of(), 1, 2, 2, null);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        HttpResponse first = postRetrieval(credential.apiKey(), RETRIEVAL_BODY);
        HttpResponse second = postRetrieval(credential.apiKey(), RETRIEVAL_BODY);
        HttpResponse limited = postRetrieval(credential.apiKey(), RETRIEVAL_BODY);

        assertThat(first.status()).isEqualTo(200);
        assertThat(second.status()).isEqualTo(200);
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.body().at("/error/code").asText()).isEqualTo("rate_limit_exceeded");
        assertThat(limited.headers().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
        assertThat(limited.headers().getFirst("X-RateLimit-Limit")).isEqualTo("1");
        assertThat(limited.headers().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(limited.headers().getFirst("X-RateLimit-Reset")).isNotBlank();
        verify(vectorStore, times(2)).similaritySearch(any(SearchRequest.class));
    }

    private void assertExternalFailure(String apiKey, int status, String code) throws Exception {
        HttpResponse response = postRetrieval(apiKey, RETRIEVAL_BODY);
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.body().at("/error/code").asText()).isEqualTo(code);
    }

    private void assertScopedFilter(Filter.Expression root) {
        assertThat(root.type()).isEqualTo(Filter.ExpressionType.AND);
        assertThat(root.left()).isInstanceOf(Filter.Expression.class);
        assertThat(root.right()).isInstanceOf(Filter.Expression.class);
        Filter.Expression tenantAndKnowledge = (Filter.Expression) root.left();
        Filter.Expression files = (Filter.Expression) root.right();

        assertThat(tenantAndKnowledge.type()).isEqualTo(Filter.ExpressionType.AND);
        Filter.Expression tenant = (Filter.Expression) tenantAndKnowledge.left();
        Filter.Expression knowledge = (Filter.Expression) tenantAndKnowledge.right();
        assertThat(tenant.type()).isEqualTo(Filter.ExpressionType.EQ);
        assertThat(((Filter.Key) tenant.left()).key()).isEqualTo("tenantId");
        assertThat(((Number) ((Filter.Value) tenant.right()).value()).longValue()).isEqualTo(tenantAId);
        assertThat(knowledge.type()).isEqualTo(Filter.ExpressionType.IN);
        assertThat(((Filter.Key) knowledge.left()).key()).isEqualTo("knowledgeId");
        assertThat(asLongs(((Filter.Value) knowledge.right()).value()))
                .containsExactly(knowledgeA1Id, knowledgeA2Id)
                .doesNotContain(knowledgeB1Id, knowledgeB2Id);

        assertThat(files.type()).isEqualTo(Filter.ExpressionType.IN);
        assertThat(((Filter.Key) files.left()).key()).isEqualTo("fileId");
        assertThat(asLongs(((Filter.Value) files.right()).value()))
                .containsExactly(enabledFileA1Id, enabledFileA2Id)
                .doesNotContain(disabledFileAId, enabledFileBId);
    }

    private List<Long> asLongs(Object value) {
        return ((Collection<?>) value).stream()
                .map(item -> ((Number) item).longValue())
                .toList();
    }

    private CreatedCredential createCredential(String name, Set<UUID> knowledgeIds, List<String> allowedIpCidrs,
                                               int requestsPerMinute, int burstCapacity, int maxConcurrency,
                                               Instant expiresAt) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", name);
        body.put("credentialType", "RAG_RETRIEVAL");
        body.put("environment", "test");
        ArrayNode scope = body.putArray("knowledgeIds");
        knowledgeIds.stream().map(UUID::toString).sorted().forEach(scope::add);
        ArrayNode cidrs = body.putArray("allowedIpCidrs");
        allowedIpCidrs.forEach(cidrs::add);
        body.put("requestsPerMinute", requestsPerMinute);
        body.put("burstCapacity", burstCapacity);
        body.put("maxConcurrency", maxConcurrency);
        if (expiresAt != null) {
            body.put("expiresAt", expiresAt.toString());
        }

        HttpResponse response = request("/tenant/api-credentials", HttpMethod.POST,
                objectMapper.writeValueAsString(body), tenantAdminJwt, MediaType.APPLICATION_JSON);
        assertThat(response.status()).isEqualTo(201);
        assertThat(response.headers().getCacheControl()).isEqualTo("no-store");
        return new CreatedCredential(
                UUID.fromString(response.body().at("/data/credential/id").asText()),
                response.body().at("/data/apiKey").asText());
    }

    private String insertAgentCredential() {
        ApiKeyCodec.IssuedKey issued = apiKeyCodec.issue(CredentialType.AGENT_INVOKE, "test");
        jdbc.update("""
                INSERT INTO api_credential
                    (public_id, tenant_id, credential_type, name, key_id, secret_digest, pepper_version,
                     environment, status, allowed_ip_cidrs, requests_per_minute, burst_capacity,
                     max_concurrency, authorization_version, display_prefix, display_last_four, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'active', '[]'::jsonb, 60, 10, 5, 1, ?, ?, ?)
                """, UUID.randomUUID(), tenantAId, CredentialType.AGENT_INVOKE.name(), "agent-only",
                issued.keyId(), issued.digest(), issued.pepperVersion(), issued.environment(),
                issued.displayPrefix(), issued.displayLastFour(), tenantAAdminId);
        return issued.rawKey();
    }

    private String login(String username) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("username", username);
        body.put("password", PASSWORD);
        HttpResponse response = request("/auth/login", HttpMethod.POST,
                objectMapper.writeValueAsString(body), null, MediaType.APPLICATION_JSON);
        assertThat(response.status()).isEqualTo(200);
        return response.body().at("/data/accessToken").asText();
    }

    private HttpResponse postRetrieval(String bearer, String body) throws Exception {
        return request("/openapi/v1/retrieval", HttpMethod.POST, body, bearer, MediaType.APPLICATION_JSON);
    }

    private HttpResponse request(String path, HttpMethod method, String body, String bearer,
                                 MediaType contentType) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path));
        if (bearer != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        }
        if (contentType != null) {
            request.header(HttpHeaders.CONTENT_TYPE, contentType.toString());
        }
        request.method(method.name(), body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        java.net.http.HttpResponse<String> response = httpClient.send(request.build(),
                BodyHandlers.ofString(StandardCharsets.UTF_8));
        String rawBody = response.body() == null ? "" : response.body();
        JsonNode json = rawBody.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(rawBody);
        HttpHeaders headers = new HttpHeaders();
        response.headers().map().forEach(headers::put);
        return new HttpResponse(response.statusCode(), json, rawBody, headers);
    }

    private Document vectorDocument() {
        UUID chunkId = UUID.fromString("30000000-0000-0000-0000-000000000001");
        return Document.builder()
                .id(chunkId.toString())
                .text("authorized result")
                .score(0.91)
                .metadata("fileId", enabledFileA1Id)
                .metadata("chunkId", chunkId.toString())
                .metadata("page_number", 2)
                .metadata("chunkIndex", 4)
                .build();
    }

    private UUID filePublicId(long fileId) {
        return jdbc.queryForObject("SELECT public_id FROM file WHERE id = ?", UUID.class, fileId);
    }

    private void insertFixtures() {
        tenantAId = insertTenant(TENANT_A_CODE, "Retrieval IT Tenant A");
        tenantBId = insertTenant(TENANT_B_CODE, "Retrieval IT Tenant B");
        tenantAAdminId = insertAdmin(tenantAId, TENANT_A_USERNAME);
        insertAdmin(tenantBId, TENANT_B_USERNAME);

        knowledgeA1PublicId = UUID.randomUUID();
        knowledgeA2PublicId = UUID.randomUUID();
        knowledgeA1Id = insertKnowledge(tenantAId, knowledgeA1PublicId, "IT knowledge A1");
        knowledgeA2Id = insertKnowledge(tenantAId, knowledgeA2PublicId, "IT knowledge A2");
        knowledgeB1Id = insertKnowledge(tenantBId, UUID.randomUUID(), "IT knowledge B1");
        knowledgeB2Id = insertKnowledge(tenantBId, UUID.randomUUID(), "IT knowledge B2");

        enabledFileA1Id = insertFile(tenantAId, "it-enabled-a1.txt", 1);
        enabledFileA2Id = insertFile(tenantAId, "it-enabled-a2.txt", 1);
        disabledFileAId = insertFile(tenantAId, "it-disabled-a.txt", 0);
        enabledFileBId = insertFile(tenantBId, "it-enabled-b.txt", 1);
        linkKnowledgeFile(tenantAId, knowledgeA1Id, enabledFileA1Id);
        linkKnowledgeFile(tenantAId, knowledgeA2Id, enabledFileA2Id);
        linkKnowledgeFile(tenantAId, knowledgeA1Id, disabledFileAId);
        linkKnowledgeFile(tenantBId, knowledgeB1Id, enabledFileBId);
    }

    private long insertTenant(String code, String name) {
        return jdbc.queryForObject(
                "INSERT INTO tenant (code, name, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, code, name);
    }

    private long insertAdmin(long tenantId, String username) {
        return jdbc.queryForObject("""
                INSERT INTO app_user (tenant_id, username, password_hash, display_name, role, status)
                VALUES (?, ?, ?, ?, 'tenant_admin', 1) RETURNING id
                """, Long.class, tenantId, username, passwordEncoder.hash(PASSWORD), username);
    }

    private long insertKnowledge(long tenantId, UUID publicId, String name) {
        return jdbc.queryForObject(
                "INSERT INTO knowledge (tenant_id, public_id, name) VALUES (?, ?, ?) RETURNING id",
                Long.class, tenantId, publicId, name);
    }

    private long insertFile(long tenantId, String name, int status) {
        return jdbc.queryForObject("""
                INSERT INTO file (tenant_id, public_id, file_name, size, status, type, path, embedding_status)
                VALUES (?, ?, ?, 100, ?, 'txt', ?, 1) RETURNING id
                """, Long.class, tenantId, UUID.randomUUID(), name, status, "/private/" + name);
    }

    private void linkKnowledgeFile(long tenantId, long knowledgeId, long fileId) {
        jdbc.update("INSERT INTO knowledge_file (tenant_id, knowledge_id, file_id) VALUES (?, ?, ?)",
                tenantId, knowledgeId, fileId);
    }

    private void cleanFixtures() {
        jdbc.update("""
                DELETE FROM api_credential
                WHERE tenant_id IN (SELECT id FROM tenant WHERE code LIKE ?)
                """, FIXTURE_PREFIX + "%");
        jdbc.update("""
                DELETE FROM refresh_token
                WHERE user_id IN (SELECT id FROM app_user WHERE tenant_id IN
                    (SELECT id FROM tenant WHERE code LIKE ?))
                """, FIXTURE_PREFIX + "%");
        jdbc.update("""
                DELETE FROM knowledge_file
                WHERE tenant_id IN (SELECT id FROM tenant WHERE code LIKE ?)
                """, FIXTURE_PREFIX + "%");
        jdbc.update("DELETE FROM file WHERE tenant_id IN (SELECT id FROM tenant WHERE code LIKE ?)",
                FIXTURE_PREFIX + "%");
        jdbc.update("DELETE FROM knowledge WHERE tenant_id IN (SELECT id FROM tenant WHERE code LIKE ?)",
                FIXTURE_PREFIX + "%");
        jdbc.update("DELETE FROM app_user WHERE tenant_id IN (SELECT id FROM tenant WHERE code LIKE ?)",
                FIXTURE_PREFIX + "%");
        jdbc.update("DELETE FROM tenant WHERE code LIKE ?", FIXTURE_PREFIX + "%");
    }

    private record CreatedCredential(UUID id, String apiKey) {
    }

    private record HttpResponse(int status, JsonNode body, String rawBody, HttpHeaders headers) {
    }
}
