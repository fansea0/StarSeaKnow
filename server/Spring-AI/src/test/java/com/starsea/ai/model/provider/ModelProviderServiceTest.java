package com.starsea.ai.model.provider;

import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.mapper.ModelProviderCatalogMapper;
import com.starsea.ai.mapper.TenantModelProviderMapper;
import com.starsea.ai.mapper.AgentModelMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelProviderServiceTest {

    private ModelProviderCatalogMapper catalogs;
    private TenantModelProviderMapper connections;
    private ModelProviderSecretCipher cipher;
    private ModelProviderConnectionVerifier verifier;
    private AgentModelMapper agentModels;
    private ModelProviderService service;

    @BeforeEach
    void setUp() {
        catalogs = mock(ModelProviderCatalogMapper.class);
        connections = mock(TenantModelProviderMapper.class);
        cipher = mock(ModelProviderSecretCipher.class);
        verifier = mock(ModelProviderConnectionVerifier.class);
        agentModels = mock(AgentModelMapper.class);
        service = new ModelProviderService(catalogs, connections, cipher, verifier, agentModels);
        AuthContext.set(new AuthContext(
                AuthContext.Kind.BUSINESS, 71L, 9L, "tenant_admin", "test-jti"));
    }

    @AfterEach
    void clearAuthContext() {
        AuthContext.clear();
    }

    @Test
    void lists_catalog_merged_with_current_tenant_connections_without_secret_material() {
        ModelProviderCatalog openAi = catalog(1L, "OPENAI", "OpenAI", "API_KEY");
        ModelProviderCatalog ollama = catalog(2L, "OLLAMA", "Ollama", "NONE");
        TenantModelProvider configured = connection(30L, 9L, 1L, "API_KEY");
        configured.setApiKeyCiphertext("ciphertext-must-not-leak");
        configured.setApiKeyNonce("nonce-must-not-leak");
        configured.setApiKeyVersion("v1");
        configured.setApiKeyLastFour("1234");
        TenantModelProvider custom = connection(31L, 9L, null, "API_KEY");
        custom.setCustomName("Acme AI");
        custom.setCustomIcon("provider/custom");
        custom.setApiKeyCiphertext("custom-ciphertext");
        custom.setApiKeyNonce("custom-nonce");
        custom.setApiKeyVersion("v1");
        custom.setApiKeyLastFour("5678");
        when(catalogs.selectList(any())).thenReturn(List.of(openAi, ollama));
        when(connections.selectList(any())).thenReturn(List.of(configured, custom));

        List<ModelProviderApiModels.ProviderView> result = service.listProviders();

        assertThat(result).extracting(ModelProviderApiModels.ProviderView::name)
                .containsExactly("OpenAI", "Ollama", "Acme AI");
        assertThat(result.get(0).configured()).isTrue();
        assertThat(result.get(0).apiKeyConfigured()).isTrue();
        assertThat(result.get(0).apiKeyLastFour()).isEqualTo("1234");
        assertThat(result.get(1).configured()).isFalse();
        assertThat(result.get(2).custom()).isTrue();
        assertThat(result.toString())
                .doesNotContain("ciphertext-must-not-leak", "nonce-must-not-leak", "custom-ciphertext");
        assertThat(configured.toString())
                .doesNotContain("ciphertext-must-not-leak", "nonce-must-not-leak");
    }

    @Test
    void verifies_before_inserting_and_encrypts_with_preallocated_connection_id() {
        ModelProviderCatalog openAi = catalog(1L, "OPENAI", "OpenAI", "API_KEY");
        when(catalogs.selectById(1L)).thenReturn(openAi);
        when(connections.nextId()).thenReturn(44L);
        when(verifier.verify("https://api.openai.com/v1", "API_KEY", "sk-test-1234"))
                .thenReturn(new ModelProviderConnectionVerifier.VerifiedConnection(List.of(
                        new ModelSuggestion("remote-model", "Remote Model", 32000))));
        when(cipher.encrypt(9L, 44L, "sk-test-1234"))
                .thenReturn(new EncryptedProviderSecret("cipher", "nonce", "v1", "1234"));
        ModelProviderApiModels.ConnectionCommand command =
                new ModelProviderApiModels.ConnectionCommand(
                        1L, null, null, null, "sk-test-1234", List.of());

        ModelProviderApiModels.ProviderView result = service.createConnection(command);

        ArgumentCaptor<TenantModelProvider> inserted = ArgumentCaptor.forClass(TenantModelProvider.class);
        verify(verifier).verify("https://api.openai.com/v1", "API_KEY", "sk-test-1234");
        verify(connections).insert(inserted.capture());
        assertThat(inserted.getValue().getId()).isEqualTo(44L);
        assertThat(inserted.getValue().getTenantId()).isEqualTo(9L);
        assertThat(inserted.getValue().getCreatedBy()).isEqualTo(71L);
        assertThat(inserted.getValue().getApiKeyCiphertext()).isEqualTo("cipher");
        assertThat(inserted.getValue().getSelectableModels())
                .extracting(ModelSuggestion::modelId)
                .containsExactly("gpt-4o-mini", "remote-model");
        assertThat(result.apiKeyConfigured()).isTrue();
        assertThat(result.toString()).doesNotContain("cipher", "nonce", "sk-test-1234");
    }

    @Test
    void failed_verification_does_not_insert_or_encrypt() {
        ModelProviderCatalog openAi = catalog(1L, "OPENAI", "OpenAI", "API_KEY");
        when(catalogs.selectById(1L)).thenReturn(openAi);
        when(verifier.verify(any(), any(), any()))
                .thenThrow(new ModelProviderException(422, "MODEL_PROVIDER_AUTH_FAILED", "厂商认证失败"));
        ModelProviderApiModels.ConnectionCommand command =
                new ModelProviderApiModels.ConnectionCommand(
                        1L, null, null, null, "wrong-key", List.of());

        assertThat(command.toString()).doesNotContain("wrong-key");

        assertThatThrownBy(() -> service.createConnection(command))
                .isInstanceOf(ModelProviderException.class)
                .hasMessage("厂商认证失败");
        verify(connections, never()).nextId();
        verify(connections, never()).insert(any());
        verify(cipher, never()).encrypt(anyLong(), anyLong(), any());
    }

    @Test
    void rejects_custom_provider_without_any_selectable_model() {
        when(verifier.verify("https://acme.test/v1", "API_KEY", "acme-key"))
                .thenReturn(new ModelProviderConnectionVerifier.VerifiedConnection(List.of()));
        ModelProviderApiModels.ConnectionCommand command =
                new ModelProviderApiModels.ConnectionCommand(
                        null, "Acme AI", "provider/custom", "https://acme.test/v1", "acme-key", List.of());

        assertThatThrownBy(() -> service.createConnection(command))
                .isInstanceOf(ModelProviderException.class)
                .hasMessage("自定义厂商至少需要一个可选模型");
        verify(connections, never()).insert(any());
    }

    @Test
    void rejects_duplicate_user_model_ids_before_contacting_provider() {
        when(catalogs.selectById(1L)).thenReturn(catalog(1L, "OPENAI", "OpenAI", "API_KEY"));
        ModelSuggestion first = new ModelSuggestion("same", "First", 8192);
        ModelSuggestion second = new ModelSuggestion("same", "Second", 16384);
        ModelProviderApiModels.ConnectionCommand command =
                new ModelProviderApiModels.ConnectionCommand(
                        1L, null, null, null, "test-key", List.of(first, second));

        assertThatThrownBy(() -> service.createConnection(command))
                .isInstanceOf(ModelProviderException.class)
                .hasMessage("同一厂商的模型 ID 不能重复");
        verify(verifier, never()).verify(any(), any(), any());
        verify(connections, never()).insert(any());
    }

    @Test
    void failed_update_verification_preserves_existing_connection() {
        TenantModelProvider existing = connection(30L, 9L, 1L, "API_KEY");
        existing.setApiKeyCiphertext("old-cipher");
        existing.setApiKeyNonce("old-nonce");
        existing.setApiKeyVersion("v1");
        existing.setApiKeyLastFour("1234");
        when(connections.selectOne(any())).thenReturn(existing);
        when(catalogs.selectById(1L)).thenReturn(catalog(1L, "OPENAI", "OpenAI", "API_KEY"));
        when(cipher.decrypt(9L, 30L, new EncryptedProviderSecret(
                "old-cipher", "old-nonce", "v1", "1234"))).thenReturn("old-plain-key");
        when(verifier.verify("https://new.example/v1", "API_KEY", "old-plain-key"))
                .thenThrow(new ModelProviderException(502, "MODEL_PROVIDER_UNAVAILABLE", "厂商连接失败"));
        ModelProviderApiModels.ConnectionCommand command =
                new ModelProviderApiModels.ConnectionCommand(
                        1L, null, null, "https://new.example/v1", null, List.of());

        assertThatThrownBy(() -> service.updateConnection(30L, command))
                .isInstanceOf(ModelProviderException.class)
                .hasMessage("厂商连接失败");

        assertThat(existing.getBaseUrl()).isEqualTo("https://example.test/v1");
        assertThat(existing.getApiKeyCiphertext()).isEqualTo("old-cipher");
        verify(connections, never()).updateById(any());
        verify(cipher, never()).encrypt(anyLong(), anyLong(), any());
    }

    @Test
    void replaces_models_but_rejects_duplicate_model_ids() {
        TenantModelProvider existing = connection(30L, 9L, 1L, "API_KEY");
        when(connections.selectOne(any())).thenReturn(existing);
        ModelSuggestion first = new ModelSuggestion("same", "First", 8192);
        ModelSuggestion second = new ModelSuggestion("same", "Second", 16384);

        assertThatThrownBy(() -> service.replaceModels(
                30L, new ModelProviderApiModels.ModelListCommand(List.of(first, second))))
                .isInstanceOf(ModelProviderException.class)
                .hasMessage("同一厂商的模型 ID 不能重复");
        verify(connections, never()).updateById(any());

        reset(connections);
        when(connections.selectOne(any())).thenReturn(existing);
        service.replaceModels(30L, new ModelProviderApiModels.ModelListCommand(List.of(first)));

        assertThat(existing.getSelectableModels()).containsExactly(first);
        verify(connections).updateById(existing);
    }

    @Test
    void rejects_removing_a_model_or_provider_connection_used_by_an_agent() {
        TenantModelProvider existing = connection(30L, 9L, 1L, "API_KEY");
        ModelSuggestion used = new ModelSuggestion("used", "Used", 8192);
        ModelSuggestion retained = new ModelSuggestion("retained", "Retained", 8192);
        existing.setSelectableModels(List.of(used, retained));
        when(connections.selectOne(any())).thenReturn(existing);
        when(agentModels.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.replaceModels(
                30L, new ModelProviderApiModels.ModelListCommand(List.of(retained))))
                .isInstanceOf(ModelProviderException.class)
                .extracting("status", "code")
                .containsExactly(409, "MODEL_PROVIDER_MODEL_IN_USE");
        verify(connections, never()).updateById(any());

        assertThatThrownBy(() -> service.deleteConnection(30L))
                .isInstanceOf(ModelProviderException.class)
                .extracting("status", "code")
                .containsExactly(409, "MODEL_PROVIDER_IN_USE");
        verify(connections, never()).deleteById(30L);
    }

    private ModelProviderCatalog catalog(Long id, String code, String name, String authType) {
        ModelProviderCatalog catalog = new ModelProviderCatalog();
        catalog.setId(id);
        catalog.setCode(code);
        catalog.setName(name);
        catalog.setIcon("provider/" + code.toLowerCase());
        catalog.setDefaultBaseUrl("OPENAI".equals(code)
                ? "https://api.openai.com/v1" : "http://localhost:11434/v1");
        catalog.setProtocolType("OPENAI_COMPATIBLE");
        catalog.setAuthType(authType);
        catalog.setSuggestedModels("OPENAI".equals(code)
                ? List.of(new ModelSuggestion("gpt-4o-mini", "GPT-4o Mini", 128000))
                : List.of());
        return catalog;
    }

    private TenantModelProvider connection(Long id, Long tenantId, Long catalogId, String authType) {
        TenantModelProvider connection = new TenantModelProvider();
        connection.setId(id);
        connection.setTenantId(tenantId);
        connection.setCatalogProviderId(catalogId);
        connection.setBaseUrl("https://example.test/v1");
        connection.setProtocolType("OPENAI_COMPATIBLE");
        connection.setAuthType(authType);
        connection.setSelectableModels(List.of());
        connection.setLastVerifiedAt(OffsetDateTime.now());
        connection.setCreatedBy(71L);
        return connection;
    }
}
