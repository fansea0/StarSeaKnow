package com.starsea.ai.openapi.credential;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.auth.AuthException;
import com.starsea.ai.domain.Knowledge;
import com.starsea.ai.mapper.KnowledgeMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
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
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), Knowledge.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "credential-test"), ApiCredential.class);
        lenient().when(credentials.update(isNull(), any())).thenReturn(1);
        service = serviceWithCodec(codec(Map.of("v1", PEPPER), "v1"));
    }

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
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

    @Test
    void rejectsCrossTenantKnowledgeWithExplicitTenantPredicateBeforeRelationInsert() {
        Knowledge crossTenantRow = knowledge(91L, KNOWLEDGE_PUBLIC_ID);
        when(knowledge.selectList(any())).thenAnswer(invocation -> {
            AbstractWrapper<?, ?, ?> query = invocation.getArgument(0);
            boolean tenantPredicate = query.getSqlSegment().contains("tenant_id")
                    && query.getParamNameValuePairs().containsValue(22L);
            return tenantPredicate ? List.of() : List.of(crossTenantRow);
        });

        assertThatThrownBy(() -> service.create(createCommand(Set.of(KNOWLEDGE_PUBLIC_ID)), tenantAdminContext()))
                .isInstanceOf(AuthException.class)
                .extracting(error -> ((AuthException) error).getCode())
                .isEqualTo(40302);

        ArgumentCaptor<Wrapper<Knowledge>> query = ArgumentCaptor.forClass(Wrapper.class);
        verify(knowledge).selectList(query.capture());
        assertThat(query.getValue().getSqlSegment()).contains("public_id", "tenant_id");
        AbstractWrapper<?, ?, ?> capturedQuery = (AbstractWrapper<?, ?, ?>) query.getValue();
        assertThat(capturedQuery.getParamNameValuePairs()).containsValues(KNOWLEDGE_PUBLIC_ID, 22L);
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
    void rejectsMoreThanFiftyDistinctKnowledgeIdsBeforeDatabaseLookup() {
        Set<UUID> tooMany = IntStream.rangeClosed(1, 51)
                .mapToObj(index -> new UUID(0L, index))
                .collect(Collectors.toSet());

        assertThatThrownBy(() -> service.create(createCommand(tooMany), tenantAdminContext()))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("at most 50");

        verify(knowledge, never()).selectList(any());
        verify(credentials, never()).insert(any());
    }

    @Test
    void rejectsOversizedReplacementBeforeCredentialOrKnowledgeDatabaseLookup() {
        Set<UUID> tooMany = IntStream.rangeClosed(1, 51)
                .mapToObj(index -> new UUID(0L, index))
                .collect(Collectors.toSet());

        assertThatThrownBy(() -> service.replaceKnowledgeBases(CREDENTIAL_PUBLIC_ID, tooMany, tenantAdminContext()))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("at most 50");

        verify(credentials, never()).selectOne(any());
        verify(knowledge, never()).selectList(any());
    }

    @Test
    void disablesAndReenablesCredentialButNeverRestoresRevokedCredential() {
        ApiCredential row = credential(41L, "old-key-id", 7L);
        when(credentials.selectOne(any())).thenReturn(row);
        when(credentialKnowledge.selectList(any())).thenReturn(List.of());

        ApiCredentialService.ApiCredentialView disabled = service.update(CREDENTIAL_PUBLIC_ID,
                new ApiCredentialService.UpdateCredentialCommand(null, null, null, null, null, null,
                        false, null, "disabled"), tenantAdminContext());
        assertThat(disabled.status()).isEqualTo("disabled");
        verify(cache).evict("old-key-id");

        row.setStatus("revoked");
        assertThatThrownBy(() -> service.update(CREDENTIAL_PUBLIC_ID,
                new ApiCredentialService.UpdateCredentialCommand(null, null, null, null, null, null,
                        false, null, "active"), tenantAdminContext()))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("revoked");
    }

    @Test
    void distinguishesAbsentExpiryFromExplicitNull() {
        ApiCredential row = credential(41L, "old-key-id", 7L);
        row.setExpiresAt(OffsetDateTime.parse("2030-01-01T00:00:00Z"));
        when(credentials.selectOne(any())).thenReturn(row);
        when(credentialKnowledge.selectList(any())).thenReturn(List.of());

        service.update(CREDENTIAL_PUBLIC_ID,
                new ApiCredentialService.UpdateCredentialCommand("new", null, null, null, null, null,
                        false, null, null), tenantAdminContext());
        assertThat(row.getExpiresAt()).isEqualTo(OffsetDateTime.parse("2030-01-01T00:00:00Z"));

        service.update(CREDENTIAL_PUBLIC_ID,
                new ApiCredentialService.UpdateCredentialCommand(null, null, null, null, null, null,
                        true, null, null), tenantAdminContext());
        assertThat(row.getExpiresAt()).isNull();
    }

    @Test
    void metadataPatchCannotOverwriteAConcurrentRevocation() {
        ApiCredential initiallyActive = credential(41L, "old-key-id", 7L);
        ApiCredential concurrentlyRevoked = credential(41L, "old-key-id", 7L);
        concurrentlyRevoked.setStatus("revoked");
        concurrentlyRevoked.setRevokedAt(OffsetDateTime.parse("2030-01-01T00:00:00Z"));
        when(credentials.selectOne(any())).thenReturn(initiallyActive, concurrentlyRevoked);
        when(credentials.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.update(CREDENTIAL_PUBLIC_ID,
                new ApiCredentialService.UpdateCredentialCommand("new name", null, null, null, null, null,
                        false, null, null), tenantAdminContext()))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("revoked");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<ApiCredential>> wrapper =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(credentials).update(isNull(), wrapper.capture());
        assertThat(wrapper.getValue().getExpression().getSqlSegment()).contains("status");
        assertThat(((com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ApiCredential>)
                wrapper.getValue()).getSqlSet()).contains("name").doesNotContain("authorization_version");
        verify(credentials, never()).updateById(any());
        assertThat(initiallyActive.getAuthorizationVersion()).isEqualTo(7L);
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
        verify(credentials).update(isNull(), any());
        assertThat(existing.getAuthorizationVersion()).isEqualTo(8L);
        verify(cache).evict("old-key-id");
    }

    @Test
    void scopeReplacementEvictsImmediatelyAndAgainAfterCommit() {
        ApiCredential existing = credential(41L, "old-key-id", 7L);
        when(credentials.selectOne(any())).thenReturn(existing);
        when(knowledge.selectList(any())).thenReturn(List.of(knowledge(92L, KNOWLEDGE_PUBLIC_ID)));
        TransactionSynchronizationManager.initSynchronization();

        service.replaceKnowledgeBases(CREDENTIAL_PUBLIC_ID, Set.of(KNOWLEDGE_PUBLIC_ID), tenantAdminContext());

        verify(cache).evict("old-key-id");
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        verify(cache, times(2)).evict("old-key-id");
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
        verify(credentials).update(isNull(), any());
        verify(cache).evict("old-key-id");
        verify(cache).evict(replacement.getKeyId());
        ArgumentCaptor<ApiCredentialKnowledge> replacementLink = ArgumentCaptor.forClass(ApiCredentialKnowledge.class);
        verify(credentialKnowledge).insert(replacementLink.capture());
        assertThat(replacementLink.getValue().getCredentialId()).isEqualTo(42L);
        assertThat(replacementLink.getValue().getKnowledgeId()).isEqualTo(91L);
    }

    @Test
    void rotationConditionsRevocationOnTheAuthorizationVersionItCopied() {
        ApiCredential old = credential(41L, "old-key-id", 7L);
        when(credentials.selectOne(any())).thenReturn(old);
        when(credentialKnowledge.selectList(any())).thenReturn(List.of());
        when(credentials.insert(any())).thenAnswer(invocation -> {
            ((ApiCredential) invocation.getArgument(0)).setId(42L);
            return 1;
        });

        service.rotate(CREDENTIAL_PUBLIC_ID, tenantAdminContext());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<ApiCredential>> wrapper =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(credentials).update(isNull(), wrapper.capture());
        assertThat(wrapper.getValue().getExpression().getSqlSegment())
                .contains("authorization_version");
    }

    @Test
    void rotationEvictsOldAndNewKeysImmediatelyAndAgainAfterCommit() {
        ApiCredential old = credential(41L, "old-key-id", 7L);
        when(credentials.selectOne(any())).thenReturn(old);
        when(credentialKnowledge.selectList(any())).thenReturn(List.of());
        when(credentials.insert(any())).thenAnswer(invocation -> {
            ((ApiCredential) invocation.getArgument(0)).setId(42L);
            return 1;
        });
        TransactionSynchronizationManager.initSynchronization();

        service.rotate(CREDENTIAL_PUBLIC_ID, tenantAdminContext());

        ArgumentCaptor<ApiCredential> inserted = ArgumentCaptor.forClass(ApiCredential.class);
        verify(credentials).insert(inserted.capture());
        String newKeyId = inserted.getValue().getKeyId();
        verify(cache).evict("old-key-id");
        verify(cache).evict(newKeyId);
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        verify(cache, times(2)).evict("old-key-id");
        verify(cache, times(2)).evict(newKeyId);
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

    @Test
    void revokeEvictsImmediatelyAndAgainAfterCommit() {
        ApiCredential active = credential(41L, "old-key-id", 7L);
        when(credentials.selectOne(any())).thenReturn(active);
        when(credentialKnowledge.selectList(any())).thenReturn(List.of());
        TransactionSynchronizationManager.initSynchronization();

        service.revoke(CREDENTIAL_PUBLIC_ID, tenantAdminContext());

        verify(cache).evict("old-key-id");
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        verify(cache, times(2)).evict("old-key-id");
    }

    @Test
    void softDeleteRevokesAndHidesCredentialWhileEvictingItsKey() {
        ApiCredential active = credential(41L, "delete-key-id", 7L);
        when(credentials.selectOne(any())).thenReturn(active);

        service.delete(CREDENTIAL_PUBLIC_ID, tenantAdminContext());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<ApiCredential>> update =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(credentials).update(isNull(), update.capture());
        assertThat(update.getValue().getSqlSet()).contains("status", "deleted_at", "revoked_at");
        verify(cache).evict("delete-key-id");
    }

    @Test
    void repeatedSoftDeleteIsIdempotentAndStillEvictsTheKey() {
        ApiCredential deleted = credential(41L, "deleted-key-id", 7L);
        deleted.setStatus("revoked");
        deleted.setDeletedAt(OffsetDateTime.parse("2029-01-01T00:00:00Z"));
        when(credentials.selectOne(any())).thenReturn(deleted);

        service.delete(CREDENTIAL_PUBLIC_ID, tenantAdminContext());

        verify(credentials, never()).update(isNull(), any());
        verify(cache).evict("deleted-key-id");
    }

    @Test
    void listFiltersSoftDeletedCredentialsAtTheDatabaseBoundary() {
        when(credentials.selectList(any())).thenReturn(List.of());

        service.list(tenantAdminContext());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<ApiCredential>> query =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(credentials).selectList(query.capture());
        assertThat(query.getValue().getExpression().getSqlSegment()).contains("deleted_at");
    }

    @Test
    void listDefensivelyDropsDeletedRowsReturnedByTheMapper() {
        ApiCredential deleted = credential(41L, "deleted-key-id", 7L);
        deleted.setDeletedAt(OffsetDateTime.parse("2029-01-01T00:00:00Z"));
        when(credentials.selectList(any())).thenReturn(List.of(deleted));

        List<ApiCredentialService.ApiCredentialView> result = service.list(tenantAdminContext());

        assertThat(result).isEmpty();
        verify(credentialKnowledge, never()).selectList(any());
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
