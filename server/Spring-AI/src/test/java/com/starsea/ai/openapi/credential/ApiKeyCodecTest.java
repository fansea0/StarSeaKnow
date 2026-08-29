package com.starsea.ai.openapi.credential;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyCodecTest {

    private static final String PEPPER_V1 = "12345678901234567890123456789012";
    private static final String PEPPER_V2 = "abcdefghijklmnopqrstuvwxyzABCDEF";
    private static final String DEVELOPMENT_DEFAULT = "dev-only-rag-pepper-v1-change-me-32bytes";

    @Test
    void issuesRagTestKeyAndNeverRequiresStoredPlaintextForVerification() {
        ApiKeyCodec codec = codec(Map.of("v1", PEPPER_V1), "v1");

        ApiKeyCodec.IssuedKey issued = codec.issue(CredentialType.RAG_RETRIEVAL, "test");

        assertThat(issued.rawKey()).startsWith("rag_test_");
        assertThat(issued.digest()).hasSize(64);
        assertThat(codec.verify(codec.parse(issued.rawKey()), issued.digest(), "v1")).isTrue();
        assertThat(issued.digest()).doesNotContain(issued.secret());
    }

    @Test
    void verifiesOldV1AfterV2BecomesActive() {
        ApiKeyCodec v1 = codec(Map.of("v1", PEPPER_V1), "v1");
        ApiKeyCodec.IssuedKey old = v1.issue(CredentialType.RAG_RETRIEVAL, "test");
        ApiKeyCodec v2 = codec(Map.of("v1", PEPPER_V1, "v2", PEPPER_V2), "v2");

        assertThat(v2.verify(v2.parse(old.rawKey()), old.digest(), "v1")).isTrue();
    }

    @Test
    void verifiesFixedDigestOverKeyIdAndSecretWithoutPrefix() {
        ApiKeyCodec codec = codec(Map.of("v1", PEPPER_V1), "v1");
        String keyId = "AAAAAAAAAAAAAAAA";
        String secret = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        String expectedDigest = "62a686a54c980d6901deaddf43317d3d57d52b43fba4af67b9b03d81f1575dfa";

        assertThat(codec.verify(codec.parse("rag_test_" + keyId + "." + secret), expectedDigest, "v1")).isTrue();
        assertThat(codec.verify(codec.parse("agt_live_" + keyId + "." + secret), expectedDigest, "v1")).isTrue();
    }

    @Test
    void defaultPepperCannotIssueLiveKey() {
        ApiKeyCodec defaultCodec = codec(Map.of("v1", DEVELOPMENT_DEFAULT), "v1");

        assertThatThrownBy(() -> defaultCodec.issue(CredentialType.RAG_RETRIEVAL, "live"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default pepper");
    }

    @Test
    void rejectsMalformedPrefix() {
        ApiKeyCodec codec = codec(Map.of("v1", PEPPER_V1), "v1");

        assertThatThrownBy(() -> codec.parse("unknown_test_keyid_abcdefghijklmnopqrstuvwxyz0123456789-_") )
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsKeysWithAnExtraSeparator() {
        ApiKeyCodec codec = codec(Map.of("v1", PEPPER_V1), "v1");

        assertThatThrownBy(() -> codec.parse("rag_test_AAAAAAAAAAAAAAAA.___________________________________________.extra"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesBase64UrlSecretsContainingUnderscores() {
        ApiKeyCodec codec = codec(Map.of("v1", PEPPER_V1), "v1");

        ApiKeyCodec.ParsedKey parsed = codec.parse("rag_test_AAAAAAAAAAAAAAAA.___________________________________________");

        assertThat(parsed.secret()).isEqualTo("___________________________________________");
    }

    @Test
    void rejectsNonBase64UrlSecret() {
        ApiKeyCodec codec = codec(Map.of("v1", PEPPER_V1), "v1");

        assertThatThrownBy(() -> codec.parse("rag_test_keyid_not+base64"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsKeyLongerThan256Characters() {
        ApiKeyCodec codec = codec(Map.of("v1", PEPPER_V1), "v1");
        String tooLong = "rag_test_keyid_" + "a".repeat(257);

        assertThatThrownBy(() -> codec.parse(tooLong))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPrefixTypeAndEnvironmentMismatch() {
        ApiKeyCodec codec = codec(Map.of("v1", PEPPER_V1), "v1");
        ApiKeyCodec.IssuedKey issued = codec.issue(CredentialType.RAG_RETRIEVAL, "test");
        ApiKeyCodec.ParsedKey mismatched = new ApiKeyCodec.ParsedKey(
                issued.credentialType().prefix() + "_" + issued.environment(),
                issued.keyId(), issued.secret(), CredentialType.AGENT_INVOKE, "live");

        assertThat(codec.verify(mismatched, issued.digest(), issued.pepperVersion())).isFalse();
    }

    private ApiKeyCodec codec(Map<String, String> peppers, String activePepperVersion) {
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.setActivePepperVersion(activePepperVersion);
        properties.setPeppers(new LinkedHashMap<>(peppers));
        properties.afterPropertiesSet();
        return new ApiKeyCodec(properties);
    }
}
