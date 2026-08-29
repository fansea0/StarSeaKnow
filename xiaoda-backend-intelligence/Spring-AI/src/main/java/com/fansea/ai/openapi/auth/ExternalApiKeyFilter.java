package com.fansea.ai.openapi.auth;

import com.fansea.ai.auth.AuthContext;
import com.fansea.ai.openapi.credential.ApiCredentialResolver;
import com.fansea.ai.openapi.credential.ApiKeyCodec;
import com.fansea.ai.openapi.credential.CredentialAuthenticationException;
import com.fansea.ai.openapi.error.ExternalApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class ExternalApiKeyFilter extends OncePerRequestFilter {

    private static final String EXTERNAL_API_PREFIX = "/openapi/v1/";
    private static final int MAX_KEY_LENGTH = 256;

    private final ApiKeyCodec codec;
    private final ApiCredentialResolver resolver;
    private final ClientIpResolver clientIpResolver;
    private final InMemoryAuthenticationAttemptLimiter authenticationAttempts;
    private final HandlerExceptionResolver exceptionResolver;

    public ExternalApiKeyFilter(ApiKeyCodec codec, ApiCredentialResolver resolver, ClientIpResolver clientIpResolver,
                                InMemoryAuthenticationAttemptLimiter authenticationAttempts,
                                HandlerExceptionResolver exceptionResolver) {
        this.codec = Objects.requireNonNull(codec, "codec");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.clientIpResolver = Objects.requireNonNull(clientIpResolver, "clientIpResolver");
        this.authenticationAttempts = Objects.requireNonNull(authenticationAttempts, "authenticationAttempts");
        this.exceptionResolver = Objects.requireNonNull(exceptionResolver, "exceptionResolver");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(EXTERNAL_API_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        AuthContext.clear();
        try {
            AuthContext authenticated = authenticate(request, response);
            if (authenticated == null) {
                return;
            }
            AuthContext.set(authenticated);
            chain.doFilter(request, response);
        } finally {
            AuthContext.clear();
        }
    }

    private AuthContext authenticate(HttpServletRequest request, HttpServletResponse response) throws ServletException {
        String clientIp = clientIpResolver.clientIp(request);
        try {
            authenticationAttempts.check(clientIp);
        } catch (ExternalApiException exception) {
            response.setHeader("Retry-After", Long.toString(authenticationAttempts.retryAfterSeconds()));
            handleFailure(request, response, exception);
            return null;
        }
        String rawKey;
        try {
            rawKey = requiredBearerKey(request);
        } catch (ExternalApiException exception) {
            recordAuthenticationFailure(request, response, clientIp, exception);
            return null;
        }
        ApiKeyCodec.ParsedKey parsedKey;
        try {
            parsedKey = codec.parse(rawKey);
        } catch (IllegalArgumentException exception) {
            recordAuthenticationFailure(request, response, clientIp, new ExternalApiException(HttpStatus.UNAUTHORIZED,
                    "authentication_failed", "Authentication failed."));
            return null;
        }
        ApiCredentialResolver.ResolvedCredential credential;
        try {
            credential = resolver.resolve(parsedKey);
        } catch (CredentialAuthenticationException exception) {
            recordAuthenticationFailure(request, response, clientIp, exception);
            return null;
        }
        try {
            enforceTransportAndIpPolicy(request, credential);
        } catch (ExternalApiException exception) {
            handleFailure(request, response, exception);
            return null;
        }
        authenticationAttempts.recordSuccess(clientIp);
        return AuthContext.external(credential);
    }

    private void recordAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                             String clientIp, Exception original) throws ServletException {
        try {
            authenticationAttempts.recordFailure(clientIp);
            handleFailure(request, response, original);
        } catch (ExternalApiException limited) {
            response.setHeader("Retry-After", Long.toString(authenticationAttempts.retryAfterSeconds()));
            handleFailure(request, response, limited);
        }
    }

    private String requiredBearerKey(HttpServletRequest request) {
        List<String> headers = Collections.list(request.getHeaders("Authorization"));
        if (headers.isEmpty()) {
            throw new ExternalApiException(HttpStatus.UNAUTHORIZED, "missing_authorization_header",
                    "Authorization header is required.", "Authorization");
        }
        if (headers.size() != 1) {
            throw invalidAuthorizationHeader();
        }
        String header = headers.get(0);
        if (header == null || !header.startsWith("Bearer ")) {
            throw invalidAuthorizationHeader();
        }
        String rawKey = header.substring("Bearer ".length());
        if (rawKey.isBlank() || rawKey.length() > MAX_KEY_LENGTH) {
            throw invalidAuthorizationHeader();
        }
        return rawKey;
    }

    private void enforceTransportAndIpPolicy(HttpServletRequest request,
                                             ApiCredentialResolver.ResolvedCredential credential) {
        if ("live".equals(credential.environment()) && clientIpResolver.requiresHttps()
                && !clientIpResolver.isSecure(request)) {
            throw new ExternalApiException(HttpStatus.BAD_REQUEST, "https_required", "HTTPS is required.");
        }
        String clientIp = clientIpResolver.clientIp(request);
        if (!credential.allowedIpCidrs().isEmpty() && credential.allowedIpCidrs().stream()
                .noneMatch(cidr -> IpCidrMatcher.matches(clientIp, cidr))) {
            throw new ExternalApiException(HttpStatus.FORBIDDEN, "ip_not_allowed", "Client IP is not allowed.");
        }
    }

    private ExternalApiException invalidAuthorizationHeader() {
        return new ExternalApiException(HttpStatus.BAD_REQUEST, "invalid_authorization_header",
                "Authorization header is invalid.", "Authorization");
    }

    private void handleFailure(HttpServletRequest request, HttpServletResponse response, Exception exception)
            throws ServletException {
        if (exceptionResolver.resolveException(request, response, null, exception) == null) {
            throw new ServletException("External API exception was not resolved", exception);
        }
    }
}
