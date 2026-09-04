package com.starsea.ai.chunking.model;

import com.starsea.ai.chunking.general.CleaningStats;

import java.util.LinkedHashMap;
import java.util.Map;

/** Persistable counts produced by source normalization and opt-in cleaning. */
public record PreprocessingSummary(
        int urlMatches,
        int urlCharactersReplaced,
        int emailMatches,
        int emailCharactersReplaced,
        int whitespaceMatches,
        int whitespaceCharactersRemoved,
        int controlCharactersRemoved,
        int emptySegmentsRemoved) {

    public PreprocessingSummary {
        if (urlMatches < 0 || urlCharactersReplaced < 0 || emailMatches < 0
                || emailCharactersReplaced < 0 || whitespaceMatches < 0
                || whitespaceCharactersRemoved < 0 || controlCharactersRemoved < 0
                || emptySegmentsRemoved < 0) {
            throw new IllegalArgumentException("preprocessing counters must not be negative");
        }
    }

    public static PreprocessingSummary empty() {
        return new PreprocessingSummary(0, 0, 0, 0, 0, 0, 0, 0);
    }

    public static PreprocessingSummary from(CleaningStats stats) {
        return new PreprocessingSummary(stats.urlMatches(), stats.urlCharactersReplaced(),
                stats.emailMatches(), stats.emailCharactersReplaced(), stats.whitespaceMatches(),
                stats.whitespaceCharactersRemoved(), stats.controlCharactersRemoved(),
                stats.emptySegmentsRemoved());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("urlMatches", urlMatches);
        values.put("urlCharactersReplaced", urlCharactersReplaced);
        values.put("emailMatches", emailMatches);
        values.put("emailCharactersReplaced", emailCharactersReplaced);
        values.put("whitespaceMatches", whitespaceMatches);
        values.put("whitespaceCharactersRemoved", whitespaceCharactersRemoved);
        values.put("controlCharactersRemoved", controlCharactersRemoved);
        values.put("emptySegmentsRemoved", emptySegmentsRemoved);
        return Map.copyOf(values);
    }

    public static PreprocessingSummary fromMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) return empty();
        return new PreprocessingSummary(integer(values, "urlMatches"),
                integer(values, "urlCharactersReplaced"), integer(values, "emailMatches"),
                integer(values, "emailCharactersReplaced"), integer(values, "whitespaceMatches"),
                integer(values, "whitespaceCharactersRemoved"), integer(values, "controlCharactersRemoved"),
                integer(values, "emptySegmentsRemoved"));
    }

    private static int integer(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }
}
