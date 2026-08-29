package com.starsea.ai.openapi.retrieval;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starsea.ai.openapi.error.ExternalApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

@Component
public class RetrievalRequestParser {

    static final int MAX_BODY_BYTES = 32 * 1024;
    private static final int MAX_QUERY_CHARACTERS = 250;
    private static final Set<String> ROOT_FIELDS = Set.of("query", "retrieval_setting");
    private static final Set<String> SETTING_FIELDS = Set.of("top_k", "score_threshold");

    private final ObjectMapper objectMapper;

    public RetrievalRequestParser(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public ParsedRetrievalRequest parse(byte[] body) {
        if (body != null && body.length > MAX_BODY_BYTES) {
            throw new ExternalApiException(HttpStatus.PAYLOAD_TOO_LARGE, "request_too_large",
                    "Request body exceeds 32 KiB.");
        }

        byte[] wireBody = body == null ? new byte[0] : body;
        JsonNode root;
        try {
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(wireBody))
                    .toString();
            try (JsonParser jsonParser = objectMapper.getFactory().createParser(json)) {
                jsonParser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                root = objectMapper.readTree(jsonParser);
                if (jsonParser.nextToken() != null) {
                    throw invalid("Request body must contain exactly one JSON value.", null);
                }
            }
        } catch (IOException exception) {
            throw invalid("Request body must be valid JSON.", null);
        }
        if (root == null || !root.isObject()) {
            throw invalid("Request body must be a JSON object.", null);
        }
        rejectUnknownFields(root, ROOT_FIELDS, "");

        JsonNode queryNode = root.get("query");
        if (queryNode == null || !queryNode.isTextual()) {
            throw invalid("query must be a string.", "query");
        }
        String query = stripUnicodeBoundarySpace(queryNode.textValue());
        int queryLength = query.codePointCount(0, query.length());
        if (queryLength < 1 || queryLength > MAX_QUERY_CHARACTERS) {
            throw invalid("query must contain between 1 and 250 characters.", "query");
        }

        int topK = 5;
        double scoreThreshold = 0.0;
        JsonNode setting = root.get("retrieval_setting");
        if (setting != null) {
            if (!setting.isObject()) {
                throw invalid("retrieval_setting must be an object.", "retrieval_setting");
            }
            rejectUnknownFields(setting, SETTING_FIELDS, "retrieval_setting.");
            JsonNode topKNode = setting.get("top_k");
            if (topKNode != null) {
                if (!topKNode.isIntegralNumber() || !topKNode.canConvertToInt()) {
                    throw invalid("retrieval_setting.top_k must be an integer.",
                            "retrieval_setting.top_k");
                }
                topK = topKNode.intValue();
                if (topK < 1 || topK > 20) {
                    throw invalid("retrieval_setting.top_k must be between 1 and 20.",
                            "retrieval_setting.top_k");
                }
            }

            JsonNode thresholdNode = setting.get("score_threshold");
            if (thresholdNode != null) {
                if (!thresholdNode.isNumber()) {
                    throw invalid("retrieval_setting.score_threshold must be a number.",
                            "retrieval_setting.score_threshold");
                }
                scoreThreshold = thresholdNode.doubleValue();
                if (!Double.isFinite(scoreThreshold) || scoreThreshold < 0.0 || scoreThreshold > 1.0) {
                    throw invalid("retrieval_setting.score_threshold must be between 0 and 1.",
                            "retrieval_setting.score_threshold");
                }
            }
        }
        return new ParsedRetrievalRequest(query, topK, scoreThreshold);
    }

    private void rejectUnknownFields(JsonNode object, Set<String> allowed, String prefix) {
        Iterator<String> fields = object.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowed.contains(field)) {
                String param = prefix + field;
                throw invalid("Unknown field: " + param + ".", param);
            }
        }
    }

    private ExternalApiException invalid(String message, String param) {
        return new ExternalApiException(HttpStatus.BAD_REQUEST, "invalid_request", message, param);
    }

    private static String stripUnicodeBoundarySpace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isUnicodeSpace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (end > start) {
            int codePoint = value.codePointBefore(end);
            if (!isUnicodeSpace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    private static boolean isUnicodeSpace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    public record ParsedRetrievalRequest(String query, int topK, double scoreThreshold) {
    }
}
