package com.starsea.ai.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class EvaluationJson {
    private EvaluationJson() {}

    static String required(JsonNode value, String field, int max) {
        JsonNode node = value.path(field);
        if (!node.isTextual() || node.asText().isBlank() || node.asText().length() > max)
            throw bad(field + " 不能为空且不能超过 " + max + " 字符");
        return node.asText().strip();
    }

    static String hash(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
