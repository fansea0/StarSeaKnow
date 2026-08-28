package com.fansea.ai.openapi.credential;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fansea.ai.auth.AuthContext;
import com.fansea.ai.auth.AuthException;
import com.fansea.ai.domain.Knowledge;
import com.fansea.ai.mapper.KnowledgeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiCredentialServiceTest {

    private static final UUID KNOWLEDGE_PUBLIC_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID CREDENTIAL_PUBLIC_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final String PEPPER = "task-five-test-pepper-at-least-32-bytes";

    @Mock
    private ApiCredentialMapper credentials;
    @Mock
    private ApiCredentialKnowledgeMapper credentialKnowledge;
    @Mock
    private KnowledgeMapper knowledge;
    @Mock
    private ApiCredentialCache cache;

    private ApiCredentialService service;

    @BeforeEach
    void setUp() {
        service = serviceWithCodec(codec(Map.of("v1", PEPPER), "v1"));
    }

    @Test
    void createsRagCredentialAndRelationsInAuthenticatedTenant() {
        when(knowledge.selectList(any())).thenReturn(List.of(knowledge(91L, KNOWLEDGE_PUBLIC_ID)));
        when(credentials.insert(any())).thenAnswer(invocation -> {
            ((ApiCredential) invocation.getArgument(0)).setId(41L);
            return 1;
        });

        ApiCredentialService.CreatedCredential result = service.create(createCommand(Set.of(KNOWLEDGE_PUBLIC_ID)), tenantAdminContext());

        assertThat(result.apiKey()).startsWith("rag_test_");
        assertThat(result.credential().knowledgeIds()).containsExactly(KNOWLEDGE_PUBLIC_ID);
        ArgumentCaptor<ApiCredential> row = ArgumentCaptor.forClass(ApiCredential.class);
        verify(credentials).insert(row.capture());
        assertThat(row.getValue().getTenantId()).isEqualTo(22L);
        assertThat(row.getValue().getCredentialType()).isEqualTo("RAG_RETRIEVAL");
        assertThat(row.getValue().getSecretDigest()).hasSize(64);
        assertThat(row.getValue().getSecretDigest()).doesNotContain(result.apiKey());
        ArgumentCaptor<ApiCredentialKnowledge> link = ArgumentCaptor.forClass(ApiCredentialKnowledge.class);
        verify(credentialKnowledge).insert(link.capture());
        assertThat(link.getValue().getTenantId()).isEqualTo(22L);
        assertThat(link.getValue().getCredentialId()).isEqualTo(41L);
        assertThat(link.getValue().getCredentialType()).isEqualTo("RAG_RETRIEVAL");
        assertThat(link.getValue().getKnowledgeId()).isEqualTo(91L);
        verify(cache).evict(row.getValue().getKeyId());
    }

    @Test
    void rejectsKnowledgeUuidThatDoesNotResolveInsideAuthenticatedTenant() {
        when(knowledge.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.create(createCommand(Set.of(KNOWLEDGE_PUBLIC_ID)), tenantAdminContext()))
                .isInstanceOf(AuthException.class)
                .extracting(error -> ((AuthException) error).getCode())
                .isEqualTo(40302);

        verify(credentials, never()).insert(any());
        verify(credentialKnowledge, never()).insert(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.0.0.999/24", "10.0.0.0/33", "2001:db8::/129", "2001:db8:::1/64", "not-a-cidr"})
    void rejectsMalformedIpv4AndIpv6CidrsBeforePersistence(String cidr) {
        ApiCredentialService.CreateCredentialCommand command = new ApiCredentialService.CreateCredentialCommand(
                "客服检索", "RAG_RETRIEVAL", "test", Set.of(), List.of(cidr), 60, 10, 5,
                Instant.parse("2030-01-01T00:00:00Z"));

        assertThatThrownBy(() -> service.create(command, tenantAdminContext()))
                .isInstanceOf(AuthException.class)
                .extracting(error -> ((AuthException) error).getCode())
                .isEqualTo(40001);

        verify(credentials, never()).insert(any());
        verify(credentialKnowledge, never()).insert(any());
    }

    @Test
    void allowsEmptyScopeAtCreationWithoutCreatingRelations() {
        when(credentials.insert(any())).thenAnswer(invocation -> {
            ((ApiCredential) invocation.getArgument(0)).setId(42L);
            return 1;
        });

        ApiCredentialService.CreatedCredential result = service.create(createCommand(Set.of()), tenantAdminContext());

        assertThat(result.credential().knowledgeIds()).isEmpty();
        verify(knowledge, never()).selectList(any());
        verify(credentialKnowledge, never()).insert(any());
    }

    @Test
    void rejectsCredentialTypesOtherThanRagRetrievalInV1() {
        ApiCredentialService.CreateCredentialCommand command = new ApiCredentialService.CreateCredentialCommand(
                "Agent", "AGENT_INVOKE", "test", Set.of(), List.of(), 60, 10, 5, null);

        assertThatThrownBy(() -> service.create(command, tenantAdminContext()))
                .isInstanceOf(AuthException.class)
                .extracting(error -> ((AuthException) error).getCode())
                .isEqualTo(40001);
        verify(credentials, never()).insert(any());
    }

    @Test
    void liveCreationWithDevelopmentDefaultPepperFailsBeforePersistence() {
        service = serviceWithCodec(codec(
                Map.of("v1", ApiKeyProperties.DEVELOPMENT_DEFAULT_PEPPER), "v1"));
        ApiCredentialService.CreateCredentialCommand command = new ApiCredentialService.CreateCredentialCommand(
                "线上检索", "RAG_RETRIEVAL", "live", Set.of(), List.of(), 60, 10, 5, null);

        assertThatThrownBy(() -> service.create(command, tenantAdminContext()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default pepper");
        verify(credentials, never()).insert(any());
    }

    @Test
    void replacesScopeAndIncrementsAuthorizationVersion() {
        ApiCredential existing = credential(41L, "old-key-id", 7L);
        when(credentials.selectOne(any())).thenReturn(existing);
        when(knowledge.selectList(any())).thenReturn(List.of(knowledge(92L, KNOWLEDGE_PUBLIC_ID)));

        ApiCredentialService.ApiCredentialView result = service.replaceKnowledgeBases(
                CREDENTIAL_PUBLIC_ID, Set.of(KNOWLEDGE_PUBLIC_ID), tenantAdminContext());

        assertThat(result.authorizationVersion()).isEqualTo(8L);
        assertThat(result.knowledgeIds()).containsExactly(KNOWLEDGE_PUBLIC_ID);
        verify(credentialKnowledge).delete(any(Wrapper.class));
        ArgumentCaptor<ApiCredentialKnowledge> link = ArgumentCaptor.forClass(ApiCredentialKnowledge.class);
        verify(credentialKnowledge).insert(link.capture());
        assertThat(link.getValue().getKnowledgeId()).isEqualTo(92L);
        verify(credentials).updateById(existing);
        assertThat(existing.getAuthorizationVersion()).isEqualTo(8L);
        verify(cache).evict("old-key-id");
    }

    @Test
    void rotationCopiesTypePolicyAndScopeAndRevokesOldCredential() {
        ApiCredential old = credential(41L, "old-key-id", 7L);
        old.setAllowedIpCidrs(List.of("10.0.0.0/24"));
        old.setRequestsPerMinute(120);
        old.setBurstCapacity(20);
        old.setMaxConcurrency(8);
        old.setExpiresAt(OffsetDateTime.parse("2030-01-01T00:00:00Z"));
        when(credentials.selectOne(any())).thenReturn(old);
        ApiCredentialKnowledge oldLink = new ApiCredentialKnowledge();
        oldLink.setKnowledgeId(91L);
        when(credentialKnowledge.selectList(any())).thenReturn(List.of(oldLink));
        when(knowledge.selectBatchIds(Set.of(91L))).thenReturn(List.of(knowledge(91L, KNOWLEDGE_PUBLIC_ID)));
        when(credentials.insert(any())).thenAnswer(invocation -> {
            ApiCredential inserted = invocation.getArgument(0);
            if (inserted != old) {
                inserted.setId(42L);
            }
            return 1;
        });

        ApiCredentialService.RotatedCredential result = service.rotate(CREDENTIAL_PUBLIC_ID, tenantAdminContext());

        assertThat(result.apiKey()).startsWith("rag_test_");
        assertThat(result.credential().id()).isNotEqualTo(CREDENTIAL_PUBLIC_ID);
        ArgumentCaptor<ApiCredential> inserted = ArgumentCaptor.forClass(ApiCredential.class);
        verify(credentials).insert(inserted.capture());
        ApiCredential replacement = inserted.getValue();
        assertThat(replacement.getCredentialType()).isEqualTo(old.getCredentialType());
        assertThat(replacement.getEnvironment()).isEqualTo(old.getEnvironment());
        assertThat(replacement.getAllowedIpCidrs()).containsExactlyElementsOf(old.getAllowedIpCidrs());
        assertThat(replacement.getRequestsPerMinute()).isEqualTo(old.getRequestsPerMinute());
        assertThat(replacement.getBurstCapacity()).isEqualTo(old.getBurstCapacity());
        assertThat(replacement.getMaxConcurrency()).isEqualTo(old.getMaxConcurrency());
        assertThat(replacement.getRotatedFromId()).isEqualTo(41L);
        assertThat(old.getStatus()).isEqualTo("revoked");
        assertThat(old.getRevokedAt()).isNotNull();
        verify(credentials).updateById(old);
        verify(cache).evict("old-key-id");
        verify(cache).evict(replacement.getKeyId());
        ArgumentCaptor<ApiCredentialKnowledge> replacementLink = ArgumentCaptor.forClass(ApiCredentialKnowledge.class);
        verify(credentialKnowledge).insert(replacementLink.capture());
        assertThat(replacementLink.getValue().getCredentialId()).isEqualTo(42L);
        assertThat(replacementLink.getValue().getKnowledgeId()).isEqualTo(91L);
    }

    @Test
    void revokeIsIdempotentAndAlwaysEvictsTheKey() {
        ApiCredential alreadyRevoked = credential(41L, "old-key-id", 7L);
        alreadyRevoked.setStatus("revoked");
        alreadyRevoked.setRevokedAt(OffsetDateTime.parse("2029-01-01T00:00:00Z"));
        when(credentials.selectOne(any())).thenReturn(alreadyRevoked);
        when(credentialKnowledge.selectList(any())).thenReturn(List.of());

        ApiCredentialService.ApiCredentialView result = service.revoke(CREDENTIAL_PUBLIC_ID, tenantAdminContext());

        assertThat(result.status()).isEqualTo("revoked");
        assertThat(result.revokedAt()).isEqualTo(Instant.parse("2029-01-01T00:00:00Z"));
        verify(credentials, never()).updateById(any());
        verify(cache).evict("old-key-id");
    }

    private ApiCredentialService.CreateCredentialCommand createCommand(Set<UUID> scope) {
        return new ApiCredentialService.CreateCredentialCommand(
                "客服检索", "RAG_RETRIEVAL", "test", scope, List.of("10.0.0.0/24", "2001:db8::/64"),
                60, 10, 5, Instant.parse("2030-01-01T00:00:00Z"));
    }

    private ApiCredential credential(long id, String keyId, long authorizationVersion) {
        ApiCredential credential = new ApiCredential();
        credential.setId(id);
        credential.setPublicId(CREDENTIAL_PUBLIC_ID);
        credential.setTenantId(22L);
        credential.setCredentialType("RAG_RETRIEVAL");
        credential.setName("客服检索");
        credential.setKeyId(keyId);
        credential.setEnvironment("test");
        credential.setStatus("active");
        credential.setAllowedIpCidrs(List.of());
        credential.setRequestsPerMinute(60);
        credential.setBurstCapacity(10);
        credential.setMaxConcurrency(5);
        credential.setAuthorizationVersion(authorizationVersion);
        credential.setDisplayPrefix("rag_test_old");
        credential.setDisplayLastFour("old4");
        credential.setCreatedAt(OffsetDateTime.parse("2028-01-01T00:00:00Z"));
        credential.setCreatedBy(7L);
        return credential;
    }

    private Knowledge knowledge(long id, UUID publicId) {
        Knowledge row = new Knowledge();
        row.setId(id);
        row.setPublicId(publicId);
        return row;
    }

    private AuthContext tenantAdminContext() {
        return new AuthContext(AuthContext.Kind.BUSINESS, 7L, 22L, "tenant_admin", "tenant-jti");
    }

    private ApiCredentialService serviceWithCodec(ApiKeyCodec codec) {
        return new ApiCredentialService(credentials, credentialKnowledge, knowledge, cache, codec);
    }

    private ApiKeyCodec codec(Map<String, String> peppers, String activePepperVersion) {
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.setActivePepperVersion(activePepperVersion);
        properties.setPeppers(new LinkedHashMap<>(peppers));
        properties.afterPropertiesSet();
        return new ApiKeyCodec(properties);
    }
}
