package com.starsea.ai.openapi.auth;

import com.starsea.ai.auth.AuthAspect;
import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.auth.AuthException;
import com.starsea.ai.mapper.PlatformAdminMapper;
import com.starsea.ai.openapi.credential.ApiCredentialResolver;
import com.starsea.ai.openapi.credential.ApiKeyCodec;
import com.starsea.ai.openapi.credential.CredentialScopeSnapshot;
import com.starsea.ai.openapi.credential.CredentialAuthenticationException;
import com.starsea.ai.openapi.credential.CredentialType;
import com.starsea.ai.openapi.credential.RagKnowledgeScopeSnapshot;
import com.starsea.ai.openapi.error.ExternalApiException;
import com.starsea.ai.openapi.error.ExternalApiExceptionHandler;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

class ExternalApiKeyFilterTest {

    private static final String RAW_KEY = "rag_test_abcdefghijklmnop.QWERTYUIOPASDFGHJKLZXCVBNM1234567890abcdef";

    @AfterEach
    void clearAuthContext() {
        AuthContext.clear();
    }

    @Test
    void validExternalKeySetsContextForChainAndAlwaysClearsIt() throws Exception {
        ApiKeyCodec codec = mock(ApiKeyCodec.class);
        ApiCredentialResolver resolver = mock(ApiCredentialResolver.class);
        when(codec.parse(RAW_KEY)).thenReturn(parsedKey());
        when(resolver.resolve(any())).thenReturn(credential("test", List.of()));
        ExternalApiKeyFilter filter = filter(codec, resolver, new ExternalApiTransportProperties());
        MockHttpServletRequest request = request("/openapi/v1/retrieval", "127.0.0.1");
        request.addHeader("Authorization", "Bearer " + RAW_KEY);

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            assertThat(AuthContext.current().getKind()).isEqualTo(AuthContext.Kind.EXTERNAL_API);
            assertThat(AuthContext.current().getCredentialType()).isEqualTo("RAG_RETRIEVAL");
            assertThat(AuthContext.current().getAuthorizationVersion()).isEqualTo(1L);
        });

        assertThat(AuthContext.current()).isNull();
        verify(codec).parse(RAW_KEY);
        verify(resolver).resolve(any());
    }

    @Test
    void missingAuthorizationHeaderReturnsStable401WithoutCallingResolver() throws Exception {
        ApiCredentialResolver resolver = mock(ApiCredentialResolver.class);
        ExternalApiKeyFilter filter = filter(mock(ApiKeyCodec.class), resolver, new ExternalApiTransportProperties());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("/openapi/v1/retrieval", "127.0.0.1"), response, (req, res) -> {
            throw new AssertionError("chain must not run");
        });

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("missing_authorization_header");
        verify(resolver, never()).resolve(any());
    }

    @Test
    void authenticationFailureClearsAnyPreexistingContext() throws Exception {
        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 9L, 3L, "tenant_admin", "old-jti"));
        ExternalApiKeyFilter filter = filter(mock(ApiKeyCodec.class), mock(ApiCredentialResolver.class),
                new ExternalApiTransportProperties());

        filter.doFilter(request("/openapi/v1/retrieval", "127.0.0.1"), new MockHttpServletResponse(),
                (req, res) -> { throw new AssertionError("chain must not run"); });

        assertThat(AuthContext.current()).isNull();
    }

    @Test
    void downstreamIllegalArgumentExceptionPropagatesInsteadOfBecomingAuthenticationFailure() {
        ApiKeyCodec codec = mock(ApiKeyCodec.class);
        ApiCredentialResolver resolver = mock(ApiCredentialResolver.class);
        when(codec.parse(RAW_KEY)).thenReturn(parsedKey());
        when(resolver.resolve(any())).thenReturn(credential("test", List.of()));
        ExternalApiKeyFilter filter = filter(codec, resolver, new ExternalApiTransportProperties());
        MockHttpServletRequest request = request("/openapi/v1/retrieval", "127.0.0.1");
        request.addHeader("Authorization", "Bearer " + RAW_KEY);

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> { throw new IllegalArgumentException("downstream failure"); }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("downstream failure");
        assertThat(AuthContext.current()).isNull();
    }

    @Test
    void duplicateAuthorizationHeadersReturn400() throws Exception {
        ExternalApiKeyFilter filter = filter(mock(ApiKeyCodec.class), mock(ApiCredentialResolver.class),
                new ExternalApiTransportProperties());
        MockHttpServletRequest request = request("/openapi/v1/retrieval", "127.0.0.1");
        request.addHeader("Authorization", "Bearer first");
        request.addHeader("Authorization", "Bearer second");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            throw new AssertionError("chain must not run");
        });

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("invalid_authorization_header");
    }

    @Test
    void oversizedAuthorizationHeaderReturns400() throws Exception {
        ExternalApiKeyFilter filter = filter(mock(ApiKeyCodec.class), mock(ApiCredentialResolver.class),
                new ExternalApiTransportProperties());
        MockHttpServletRequest request = request("/openapi/v1/retrieval", "127.0.0.1");
        request.addHeader("Authorization", "Bearer " + "a".repeat(257));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            throw new AssertionError("chain must not run");
        });

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("invalid_authorization_header");
    }

    @Test
    void nonExternalRouteBypassesExternalFilter() throws Exception {
        ExternalApiKeyFilter filter = filter(mock(ApiKeyCodec.class), mock(ApiCredentialResolver.class),
                new ExternalApiTransportProperties());
        AtomicBoolean called = new AtomicBoolean();

        filter.doFilter(request("/auth/me", "127.0.0.1"), new MockHttpServletResponse(),
                (req, res) -> called.set(true));

        assertThat(called).isTrue();
    }

    @Test
    void externalKeyCannotPassRequireLogin() throws Throwable {
        AuthContext.set(AuthContext.external(credential("test", List.of())));
        AuthAspect aspect = new AuthAspect(mock(PlatformAdminMapper.class));
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);

        assertThatThrownBy(() -> aspect.requireLogin(joinPoint))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("external API credential");
    }

    @Test
    void jwtFilterSkipsExternalRoutes() {
        ExposedJwtAuthFilter filter = new ExposedJwtAuthFilter(
                mock(com.starsea.ai.auth.JwtService.class), mock(com.starsea.ai.mapper.TenantMapper.class));

        assertThat(filter.skips(request("/openapi/v1/retrieval", "127.0.0.1"))).isTrue();
    }

    @Test
    void cidrMatcherSupportsIpv4AndIpv6Networks() {
        assertThat(IpCidrMatcher.matches("192.0.2.45", "192.0.2.0/24")).isTrue();
        assertThat(IpCidrMatcher.matches("192.0.3.45", "192.0.2.0/24")).isFalse();
        assertThat(IpCidrMatcher.matches("2001:db8:1::7", "2001:db8:1::/64")).isTrue();
        assertThat(IpCidrMatcher.matches("2001:db8:2::7", "2001:db8:1::/64")).isFalse();
    }

    @Test
    void cidrMatcherRejectsHostnamesAndMalformedLiteralsWithoutTreatingThemAsAddresses() {
        assertThat(IpCidrMatcher.isIpLiteral("cafe")).isFalse();
        assertThat(IpCidrMatcher.isIpLiteral("deadbeef")).isFalse();
        assertThat(IpCidrMatcher.isIpLiteral("999.0.0.1")).isFalse();
        assertThat(IpCidrMatcher.isIpLiteral("2001:db8:::1")).isFalse();
        assertThat(IpCidrMatcher.isIpLiteral("192.0.2.1::")).isFalse();
        assertThat(IpCidrMatcher.isIpLiteral("２００１:db8::1")).isFalse();
        assertThat(IpCidrMatcher.matches("cafe", "2001:db8::/32")).isFalse();
    }

    @Test
    void untrustedCallerCannotSpoofForwardedIpOrScheme() {
        ExternalApiTransportProperties properties = new ExternalApiTransportProperties();
        properties.setTrustedProxies(List.of("10.0.0.0/8"));
        ClientIpResolver clientIpResolver = new ClientIpResolver(properties);
        MockHttpServletRequest request = request("/openapi/v1/retrieval", "198.51.100.9");
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        request.addHeader("X-Forwarded-Proto", "https");

        assertThat(clientIpResolver.clientIp(request)).isEqualTo("198.51.100.9");
        assertThat(clientIpResolver.isSecure(request)).isFalse();
    }

    @Test
    void trustedProxySuppliesFirstForwardedIpAndScheme() {
        ExternalApiTransportProperties properties = new ExternalApiTransportProperties();
        properties.setTrustedProxies(List.of("10.0.0.0/8"));
        ClientIpResolver clientIpResolver = new ClientIpResolver(properties);
        MockHttpServletRequest request = request("/openapi/v1/retrieval", "10.0.0.4");
        request.addHeader("X-Forwarded-For", "2001:db8::17, 10.0.0.1");
        request.addHeader("X-Forwarded-Proto", "https");

        assertThat(clientIpResolver.clientIp(request)).isEqualTo("2001:db8::17");
        assertThat(clientIpResolver.isSecure(request)).isTrue();
    }

    @Test
    void ipAllowlistRejectsBeforeChain() throws Exception {
        ApiKeyCodec codec = mock(ApiKeyCodec.class);
        ApiCredentialResolver resolver = mock(ApiCredentialResolver.class);
        when(codec.parse(RAW_KEY)).thenReturn(parsedKey());
        when(resolver.resolve(any())).thenReturn(credential("test", List.of("192.0.2.0/24")));
        ExternalApiKeyFilter filter = filter(codec, resolver, new ExternalApiTransportProperties());
        MockHttpServletRequest request = request("/openapi/v1/retrieval", "198.51.100.9");
        request.addHeader("Authorization", "Bearer " + RAW_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            throw new AssertionError("chain must not run");
        });

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ip_not_allowed");
    }

    @Test
    void liveKeyRequiresHttpsWhenEnabled() throws Exception {
        ApiKeyCodec codec = mock(ApiKeyCodec.class);
        ApiCredentialResolver resolver = mock(ApiCredentialResolver.class);
        when(codec.parse(RAW_KEY)).thenReturn(parsedKey());
        when(resolver.resolve(any())).thenReturn(credential("live", List.of()));
        ExternalApiTransportProperties properties = new ExternalApiTransportProperties();
        properties.setRequireHttps(true);
        ExternalApiKeyFilter filter = filter(codec, resolver, properties);
        MockHttpServletRequest request = request("/openapi/v1/retrieval", "127.0.0.1");
        request.addHeader("Authorization", "Bearer " + RAW_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            throw new AssertionError("chain must not run");
        });

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("https_required");
    }

    @Test
    void repeatedAuthenticationFailuresFromTrustedResolvedClientIpStopDatabaseResolution() throws Exception {
        ApiKeyCodec codec = mock(ApiKeyCodec.class);
        ApiCredentialResolver resolver = mock(ApiCredentialResolver.class);
        when(codec.parse(RAW_KEY)).thenReturn(parsedKey());
        when(resolver.resolve(any())).thenThrow(new CredentialAuthenticationException("authentication_failed"));
        ExternalApiTransportProperties properties = new ExternalApiTransportProperties();
        properties.setTrustedProxies(List.of("10.0.0.0/8"));
        ExternalApiKeyFilter filter = filter(codec, resolver, properties);

        MockHttpServletResponse last = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            MockHttpServletRequest request = request("/openapi/v1/retrieval", "10.0.0.4");
            request.addHeader("X-Forwarded-For", "203.0.113.8");
            request.addHeader("Authorization", "Bearer " + RAW_KEY);
            last = new MockHttpServletResponse();
            filter.doFilter(request, last, (req, res) -> { throw new AssertionError("chain must not run"); });
        }

        verify(resolver, times(5)).resolve(any());
        assertThat(last.getStatus()).isEqualTo(429);
        assertThat(last.getContentAsString()).contains("authentication_rate_limited");
    }

    @Test
    void disallowedIpCredentialCannotErasePriorAuthenticationFailures() throws Exception {
        ApiKeyCodec codec = mock(ApiKeyCodec.class);
        ApiCredentialResolver resolver = mock(ApiCredentialResolver.class);
        when(codec.parse(RAW_KEY)).thenReturn(parsedKey());
        CredentialAuthenticationException failure = new CredentialAuthenticationException("authentication_failed");
        when(resolver.resolve(any()))
                .thenThrow(failure, failure, failure, failure)
                .thenReturn(credential("test", List.of("192.0.2.0/24")))
                .thenThrow(failure);
        ExternalApiKeyFilter filter = filter(codec, resolver, new ExternalApiTransportProperties());

        for (int attempt = 0; attempt < 4; attempt++) {
            assertThat(authenticate(filter, "198.51.100.9").getStatus()).isEqualTo(401);
        }
        assertThat(authenticate(filter, "198.51.100.9").getStatus()).isEqualTo(403);
        MockHttpServletResponse blocked = authenticate(filter, "198.51.100.9");

        assertThat(blocked.getStatus()).isEqualTo(429);
        verify(resolver, times(6)).resolve(any());
    }

    @Test
    void successfulCredentialDoesNotResetIpFailureBudget() throws Exception {
        ApiKeyCodec codec = mock(ApiKeyCodec.class);
        ApiCredentialResolver resolver = mock(ApiCredentialResolver.class);
        when(codec.parse(RAW_KEY)).thenReturn(parsedKey());
        CredentialAuthenticationException failure = new CredentialAuthenticationException("authentication_failed");
        when(resolver.resolve(any()))
                .thenThrow(failure, failure, failure, failure)
                .thenReturn(credential("test", List.of()))
                .thenThrow(failure);
        ExternalApiKeyFilter filter = filter(codec, resolver, new ExternalApiTransportProperties());

        for (int attempt = 0; attempt < 4; attempt++) {
            assertThat(authenticate(filter, "198.51.100.9").getStatus()).isEqualTo(401);
        }
        assertThat(authenticate(filter, "198.51.100.9").getStatus()).isEqualTo(200);
        MockHttpServletResponse blocked = authenticate(filter, "198.51.100.9");

        assertThat(blocked.getStatus()).isEqualTo(429);
        verify(resolver, times(6)).resolve(any());
    }

    @Test
    void externalErrorHandlerDoesNotReflectApiKeyLikeOrControlCharacterRequestIds() {
        ExternalApiExceptionHandler handler = new ExternalApiExceptionHandler();
        MockHttpServletRequest apiKeyRequest = request("/openapi/v1/retrieval", "127.0.0.1");
        apiKeyRequest.addHeader("X-Request-ID", RAW_KEY);

        ResponseEntity<ExternalApiExceptionHandler.ErrorEnvelope> apiKeyResponse = handler.external(
                new ExternalApiException(HttpStatus.BAD_REQUEST, "invalid_request", "Invalid request."), apiKeyRequest);

        assertThat(apiKeyResponse.getHeaders().getFirst("X-Request-ID")).isNotEqualTo(RAW_KEY);
        assertThat(apiKeyResponse.getBody().request_id()).isEqualTo(apiKeyResponse.getHeaders().getFirst("X-Request-ID"));
        MockHttpServletRequest controlRequest = request("/openapi/v1/retrieval", "127.0.0.1");
        controlRequest.addHeader("X-Request-ID", "request\nkey");

        ResponseEntity<ExternalApiExceptionHandler.ErrorEnvelope> controlResponse = handler.external(
                new ExternalApiException(HttpStatus.BAD_REQUEST, "invalid_request", "Invalid request."), controlRequest);

        assertThat(controlResponse.getHeaders().getFirst("X-Request-ID")).isNotEqualTo("request\nkey");
        assertThat(controlResponse.getBody().request_id()).matches("[A-Za-z0-9._-]{1,64}");
    }

    @Test
    void externalAdviceMapsDisabledTo403ButKeepsInvalidAndRevokedAuthenticationAt401() {
        ExternalApiExceptionHandler handler = new ExternalApiExceptionHandler();
        MockHttpServletRequest request = request("/openapi/v1/retrieval", "127.0.0.1");

        ResponseEntity<ExternalApiExceptionHandler.ErrorEnvelope> disabled = handler.credential(
                new CredentialAuthenticationException("credential_disabled"), request);
        ResponseEntity<ExternalApiExceptionHandler.ErrorEnvelope> invalid = handler.credential(
                new CredentialAuthenticationException("authentication_failed"), request);
        ResponseEntity<ExternalApiExceptionHandler.ErrorEnvelope> revoked = handler.credential(
                new CredentialAuthenticationException("credential_revoked"), request);

        assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(disabled.getBody().error().code()).isEqualTo("credential_disabled");
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(invalid.getBody().error().code()).isEqualTo("authentication_failed");
        assertThat(revoked.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(revoked.getBody().error().code()).isEqualTo("authentication_failed");
    }

    private ExternalApiKeyFilter filter(ApiKeyCodec codec, ApiCredentialResolver resolver,
                                        ExternalApiTransportProperties properties) {
        HandlerExceptionResolver exceptionResolver = (request, response, handler, exception) -> {
            ExternalApiException external = exception instanceof ExternalApiException value ? value
                    : new ExternalApiException(HttpStatus.UNAUTHORIZED, "authentication_failed", "Authentication failed.");
            response.setStatus(external.getStatus().value());
            response.setContentType("application/json");
            try {
                response.getWriter().write("{\"error\":{\"code\":\"" + external.getCode() + "\"}}");
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
            return new org.springframework.web.servlet.ModelAndView();
        };
        return new ExternalApiKeyFilter(codec, resolver, new ClientIpResolver(properties),
                new InMemoryAuthenticationAttemptLimiter(properties), exceptionResolver);
    }

    private MockHttpServletResponse authenticate(ExternalApiKeyFilter filter, String remoteAddress) throws Exception {
        MockHttpServletRequest request = request("/openapi/v1/retrieval", remoteAddress);
        request.addHeader("Authorization", "Bearer " + RAW_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> response.setStatus(200));
        return response;
    }

    private MockHttpServletRequest request(String path, String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        request.setRemoteAddr(remoteAddress);
        return request;
    }

    private ApiKeyCodec.ParsedKey parsedKey() {
        return new ApiKeyCodec.ParsedKey("rag_test", "key-id", "secret", CredentialType.RAG_RETRIEVAL, "test");
    }

    private ApiCredentialResolver.ResolvedCredential credential(String environment, List<String> allowedIpCidrs) {
        CredentialScopeSnapshot scope = new RagKnowledgeScopeSnapshot(Set.of(12L));
        return new ApiCredentialResolver.ResolvedCredential(8L, 3L, CredentialType.RAG_RETRIEVAL, environment,
                "active", null, allowedIpCidrs, 60, 10, 2, 1L, scope);
    }

    private static final class ExposedJwtAuthFilter extends com.starsea.ai.auth.JwtAuthFilter {
        private ExposedJwtAuthFilter(com.starsea.ai.auth.JwtService jwtService,
                                     com.starsea.ai.mapper.TenantMapper tenants) {
            super(jwtService, tenants);
        }

        private boolean skips(MockHttpServletRequest request) {
            return shouldNotFilter(request);
        }
    }
}
