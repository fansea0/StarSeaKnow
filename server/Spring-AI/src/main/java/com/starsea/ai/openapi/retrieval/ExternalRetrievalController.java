package com.starsea.ai.openapi.retrieval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.openapi.credential.CredentialType;
import com.starsea.ai.openapi.credential.RagKnowledgeScopeSnapshot;
import com.starsea.ai.openapi.error.ExternalApiException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/openapi/v1")
public class ExternalRetrievalController {

    private static final int MAX_CHUNK_BYTES = 8 * 1024;
    private static final int MAX_RESPONSE_BYTES = 128 * 1024;
    private static final int DEFAULT_REQUESTS_PER_MINUTE = 60;
    private static final int DEFAULT_BURST_CAPACITY = 10;
    private static final int DEFAULT_MAX_CONCURRENCY = 5;
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern API_KEY_SHAPE =
            Pattern.compile("[a-z]+_[a-z0-9-]+_[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

    private final RetrievalRequestParser parser;
    private final RetrievalDeadlineExecutor retrievalExecutor;
    private final CredentialRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<MeterRegistry> meterRegistries;

    public ExternalRetrievalController(RetrievalRequestParser parser, RetrievalDeadlineExecutor retrievalExecutor,
                                       CredentialRateLimiter rateLimiter, ObjectMapper objectMapper,
                                       ObjectProvider<MeterRegistry> meterRegistries) {
        this.parser = parser;
        this.retrievalExecutor = retrievalExecutor;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
        this.meterRegistries = meterRegistries;
    }

    @PostMapping("/retrieval")
    public ResponseEntity<RetrievalResponse> retrieve(HttpServletRequest request,
                                                       HttpServletResponse response) {
        long startedNanos = System.nanoTime();
        String credentialType = "none";
        try {
            requireJsonContentType(request);
            AuthContext context = requireExternalContext();
            credentialType = context.getCredentialType();
            RagKnowledgeScopeSnapshot scope = requireRagScope(context);
            RetrievalRequestParser.ParsedRetrievalRequest parsed = parser.parse(readBody(request));

            int requestsPerMinute = positiveOrDefault(context.getRequestsPerMinute(),
                    DEFAULT_REQUESTS_PER_MINUTE);
            int burstCapacity = positiveOrDefault(context.getBurstCapacity(), DEFAULT_BURST_CAPACITY);
            int maxConcurrency = positiveOrDefault(context.getMaxConcurrency(), DEFAULT_MAX_CONCURRENCY);
            CredentialRateLimiter.RateLimitLease lease;
            try {
                lease = rateLimiter.acquire(context.getCredentialId(), requestsPerMinute, burstCapacity,
                        maxConcurrency);
            } catch (CredentialRateLimiter.RateLimitExceededException exception) {
                response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()));
                response.setHeader("X-RateLimit-Limit", Integer.toString(exception.limit()));
                response.setHeader("X-RateLimit-Remaining", Integer.toString(exception.remaining()));
                response.setHeader("X-RateLimit-Reset", Long.toString(exception.resetEpochSecond()));
                throw exception;
            }

            List<RetrievedChunk> chunks;
            try {
                chunks = retrievalExecutor.retrieve(new RetrievalQuery(parsed.query(), scope.knowledgeIds(),
                        parsed.topK(), parsed.scoreThreshold()), context, lease);
            } catch (TimeoutException exception) {
                throw mapRetrievalFailure(exception);
            } catch (RuntimeException exception) {
                throw mapRetrievalFailure(exception);
            }

            RetrievalResponse payload = boundedResponse(chunks);
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Request-ID", requestId(request));
            headers.set("X-RateLimit-Limit", Integer.toString(lease.limit()));
            headers.set("X-RateLimit-Remaining", Integer.toString(lease.remaining()));
            headers.set("X-RateLimit-Reset", Long.toString(lease.resetEpochSecond()));
            headers.setCacheControl(CacheControl.noStore());
            recordMetric("success", HttpStatus.OK.value(), credentialType, startedNanos);
            return new ResponseEntity<>(payload, headers, HttpStatus.OK);
        } catch (ExternalApiException exception) {
            recordMetric("error", exception.getStatus().value(), credentialType, startedNanos);
            throw exception;
        } catch (RuntimeException exception) {
            recordMetric("error", HttpStatus.INTERNAL_SERVER_ERROR.value(), credentialType, startedNanos);
            throw new ExternalApiException(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                    "Internal server error.");
        }
    }

    private AuthContext requireExternalContext() {
        AuthContext context = AuthContext.current();
        if (context == null || context.getKind() != AuthContext.Kind.EXTERNAL_API
                || context.getCredentialId() == null) {
            throw new ExternalApiException(HttpStatus.UNAUTHORIZED, "authentication_failed",
                    "Authentication failed.");
        }
        return context;
    }

    private RagKnowledgeScopeSnapshot requireRagScope(AuthContext context) {
        if (!CredentialType.RAG_RETRIEVAL.name().equals(context.getCredentialType())) {
            throw new ExternalApiException(HttpStatus.FORBIDDEN, "credential_type_not_allowed",
                    "Credential type is not allowed for this endpoint.");
        }
        if (!(context.getCredentialScope() instanceof RagKnowledgeScopeSnapshot scope)
                || scope.knowledgeIds().isEmpty()) {
            throw new ExternalApiException(HttpStatus.FORBIDDEN, "credential_has_no_knowledge_scope",
                    "Credential has no knowledge scope.");
        }
        return scope;
    }

    private void requireJsonContentType(HttpServletRequest request) {
        String contentType = request.getHeader(HttpHeaders.CONTENT_TYPE);
        try {
            MediaType mediaType = contentType == null ? null : MediaType.parseMediaType(contentType);
            if (mediaType != null
                    && "application".equalsIgnoreCase(mediaType.getType())
                    && "json".equalsIgnoreCase(mediaType.getSubtype())) {
                return;
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid media types use the same stable external contract as unsupported media types.
        }
        throw new ExternalApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "invalid_request",
                "Content-Type must be application/json.", "Content-Type");
    }

    private byte[] readBody(HttpServletRequest request) {
        try {
            return request.getInputStream().readNBytes(RetrievalRequestParser.MAX_BODY_BYTES + 1);
        } catch (IOException exception) {
            throw new ExternalApiException(HttpStatus.BAD_REQUEST, "invalid_request",
                    "Request body could not be read.");
        }
    }

    private RetrievalResponse boundedResponse(List<RetrievedChunk> chunks) {
        List<RetrievedChunk> safeChunks = chunks == null ? List.of() : chunks;
        List<RetrievalRecord> records = new ArrayList<>(safeChunks.stream()
                .filter(chunk -> chunk != null)
                .map(this::record)
                .toList());
        RetrievalResponse response = new RetrievalResponse(List.copyOf(records));
        while (!records.isEmpty() && serializedSize(response) > MAX_RESPONSE_BYTES) {
            records.remove(records.size() - 1);
            response = new RetrievalResponse(List.copyOf(records));
        }
        return response;
    }

    private RetrievalRecord record(RetrievedChunk chunk) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("document_id", text(chunk.documentId()));
        metadata.put("chunk_id", text(chunk.chunkId()));
        metadata.put("file_type", chunk.fileType());
        metadata.put("chunk_index", chunk.chunkIndex());
        metadata.put("section_path", chunk.sectionPath());
        metadata.put("start_line", sourceValue(chunk.sourceLocator(), "startLine", "start_line"));
        metadata.put("end_line", sourceValue(chunk.sourceLocator(), "endLine", "end_line"));
        double score = Double.isFinite(chunk.score()) ? Math.max(0.0, Math.min(1.0, chunk.score())) : 0.0;
        return new RetrievalRecord(truncateUtf8(chunk.content(), MAX_CHUNK_BYTES), score,
                chunk.title() == null ? "" : chunk.title(),
                Collections.unmodifiableMap(new LinkedHashMap<>(metadata)));
    }

    private int serializedSize(RetrievalResponse response) {
        try {
            return objectMapper.writeValueAsBytes(response).length;
        } catch (JsonProcessingException exception) {
            throw new ExternalApiException(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                    "Internal server error.");
        }
    }

    private ExternalApiException mapRetrievalFailure(Throwable exception) {
        if (hasCause(exception, TimeoutException.class) || hasCauseName(exception, "Timeout")) {
            return new ExternalApiException(HttpStatus.GATEWAY_TIMEOUT, "retrieval_timeout",
                    "Retrieval timed out.");
        }
        if (hasCause(exception, RejectedExecutionException.class)
                || hasCause(exception, DataAccessException.class)
                || hasPackageCause(exception, "org.springframework.ai")) {
            return new ExternalApiException(HttpStatus.SERVICE_UNAVAILABLE, "retrieval_unavailable",
                    "Retrieval is temporarily unavailable.");
        }
        return new ExternalApiException(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "Internal server error.");
    }

    private void recordMetric(String outcome, int status, String credentialType, long startedNanos) {
        MeterRegistry registry = meterRegistries.getIfAvailable();
        if (registry == null) {
            return;
        }
        Tags tags = Tags.of("outcome", outcome, "status", Integer.toString(status),
                "type", credentialType == null ? "none" : credentialType);
        Counter.builder("external.retrieval.requests").tags(tags).register(registry).increment();
        Timer.builder("external.retrieval.duration").tags(tags).register(registry)
                .record(Math.max(0, System.nanoTime() - startedNanos), TimeUnit.NANOSECONDS);
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCauseName(Throwable throwable, String fragment) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current.getClass().getSimpleName().contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPackageCause(Throwable throwable, String packagePrefix) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            Package typePackage = current.getClass().getPackage();
            if (typePackage != null && typePackage.getName().startsWith(packagePrefix)) {
                return true;
            }
        }
        return false;
    }

    private static String truncateUtf8(String content, int maxBytes) {
        String safe = content == null ? "" : content;
        byte[] bytes = safe.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return safe;
        }
        int end = maxBytes;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    private static String text(UUID value) {
        return value == null ? null : value.toString();
    }

    private static Object sourceValue(Map<String, Object> sourceLocator, String... keys) {
        if (sourceLocator == null) {
            return null;
        }
        for (String key : keys) {
            Object value = sourceLocator.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static int positiveOrDefault(Integer value, int defaultValue) {
        return value == null || value < 1 ? defaultValue : value;
    }

    private static String requestId(HttpServletRequest request) {
        String supplied = request.getHeader("X-Request-ID");
        return supplied != null && SAFE_REQUEST_ID.matcher(supplied).matches()
                && !API_KEY_SHAPE.matcher(supplied).matches() ? supplied : UUID.randomUUID().toString();
    }

    public record RetrievalRecord(String content, double score, String title, Map<String, Object> metadata) {
    }

    public record RetrievalResponse(List<RetrievalRecord> records) {
    }
}
