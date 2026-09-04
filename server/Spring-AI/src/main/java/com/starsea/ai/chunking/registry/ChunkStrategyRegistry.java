package com.starsea.ai.chunking.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starsea.ai.chunking.api.ChunkingException;
import com.starsea.ai.chunking.model.ChunkPolicy;
import com.starsea.ai.chunking.model.ChunkStrategyConfig;
import com.starsea.ai.chunking.model.ContextConfig;
import com.starsea.ai.chunking.model.ContextMode;
import com.starsea.ai.chunking.model.GeneralChunkConfig;
import com.starsea.ai.chunking.model.OverlapUnit;
import com.starsea.ai.chunking.model.ValidatedPreviewConfig;
import com.starsea.ai.chunking.spi.ChunkPlanningStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Looks up only concrete strategies registered in the Spring application context. */
@Component
public final class ChunkStrategyRegistry {

    private final List<ChunkPlanningStrategy> strategies;
    private final ObjectMapper objectMapper;

    public ChunkStrategyRegistry(List<ChunkPlanningStrategy> strategies) {
        this(strategies, new ObjectMapper());
    }

    @Autowired
    public ChunkStrategyRegistry(List<ChunkPlanningStrategy> strategies, ObjectMapper objectMapper) {
        this.strategies = List.copyOf(strategies);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        validateNoDuplicateRegistrations();
    }

    public ChunkPlanningStrategy require(String code, String fileType) {
        String normalizedCode = normalizeCode(code);
        String normalizedFileType = normalizeFileType(fileType);
        return strategies.stream()
                .filter(strategy -> normalizedCode.equals(normalizeCode(strategy.code())))
                .filter(strategy -> isGlobal(strategy.descriptor())
                        || strategy.supportedFileTypes().stream()
                            .map(ChunkStrategyRegistry::normalizeFileType)
                            .anyMatch(normalizedFileType::equals))
                .findFirst()
                .orElseThrow(() -> new ChunkStrategyNotFoundException(code, fileType));
    }

    public List<ChunkPlanningStrategy> matching(String fileType) {
        String normalizedFileType = normalizeFileType(fileType);
        return strategies.stream()
                .filter(strategy -> isGlobal(strategy.descriptor())
                        || strategy.supportedFileTypes().stream()
                        .map(ChunkStrategyRegistry::normalizeFileType)
                        .anyMatch(normalizedFileType::equals))
                .toList();
    }

    /** Converts and jointly validates request configuration before asynchronous dispatch. */
    public ValidatedPreviewConfig validatePreviewConfig(
            String code,
            String fileType,
            Map<String, Object> rawStrategyConfig,
            Map<String, Object> rawContextConfig) {
        ChunkPlanningStrategy strategy;
        try {
            strategy = require(code, fileType);
        } catch (RuntimeException exception) {
            throw invalid("strategyCode", "The requested chunk strategy is not available for this file");
        }
        String normalizedCode = normalizeCode(strategy.code());
        try {
            if ("GENERAL".equals(normalizedCode)) {
                validateKeys(rawStrategyConfig, Set.of("delimiter", "delimiterMode", "maxCharacters",
                        "collapseWhitespace", "removeUrls", "removeEmails"));
                validateKeys(rawContextConfig, Set.of("enabled", "overlapEnabled", "limit", "overlapTokens"));
                GeneralChunkConfig config = convertWithDefaults(
                        rawStrategyConfig, generalDefaults(), GeneralChunkConfig.class);
                ContextConfig context = context(rawContextConfig, strategy.descriptor().defaultContextConfig(),
                        OverlapUnit.CHARACTERS, ContextMode.CHARACTER_TAIL, true);
                if (context.enabled() && context.limit() + 5 >= config.maxCharacters()) {
                    throw invalid("limit", "overlap plus its format must be smaller than maxCharacters");
                }
                return new ValidatedPreviewConfig(config, context, ChunkPolicy.MAX_ALLOWED_TOKENS);
            }
            if ("MARKDOWN_OPTIMIZED".equals(normalizedCode)) {
                validateKeys(rawStrategyConfig, Set.of("minTokens", "targetTokens", "maxTokens"));
                validateKeys(rawContextConfig, Set.of("enabled", "overlapEnabled", "limit", "overlapTokens"));
                ChunkPolicy config = convertWithDefaults(rawStrategyConfig, markdownDefaults(), ChunkPolicy.class);
                ContextConfig context = context(rawContextConfig, strategy.descriptor().defaultContextConfig(),
                        OverlapUnit.TOKENS, ContextMode.COMPLETE_SENTENCE, false);
                return new ValidatedPreviewConfig(config, context, ChunkPolicy.MAX_ALLOWED_TOKENS);
            }
            throw invalid("strategyCode", "The strategy does not declare a supported config contract");
        } catch (ChunkingException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid(fieldFrom(exception), rootMessage(exception));
        }
    }

    private void validateKeys(Map<String, Object> values, Set<String> allowed) {
        if (values == null) return;
        values.keySet().stream()
                .filter(key -> !allowed.contains(key))
                .findFirst()
                .ifPresent(key -> {
                    throw invalid(key, key + " is not supported for this strategy");
                });
    }

    private <T> T convertWithDefaults(Map<String, Object> raw, Map<String, Object> defaults, Class<T> type) {
        Map<String, Object> merged = new LinkedHashMap<>(defaults);
        if (raw != null) {
            merged.putAll(raw);
        }
        return objectMapper.convertValue(merged, type);
    }

    private ContextConfig context(Map<String, Object> raw, ContextConfig defaults,
                                  OverlapUnit unit, ContextMode mode, boolean normalizeZero) {
        Map<String, Object> values = raw == null ? Map.of() : raw;
        rejectBooleanAliasConflict(values, "enabled", "overlapEnabled", defaults.enabled());
        rejectIntegerAliasConflict(values, "limit", "overlapTokens", defaults.limit());
        boolean enabled = booleanValue(values,
                values.containsKey("enabled") ? "enabled" : "overlapEnabled", defaults.enabled());
        int limit = intValue(values,
                values.containsKey("limit") ? "limit" : "overlapTokens", defaults.limit());
        if (normalizeZero && limit == 0) {
            enabled = false;
        }
        return new ContextConfig(enabled, limit, unit, mode);
    }

    private void rejectBooleanAliasConflict(Map<String, Object> values, String field,
                                            String legacyField, boolean fallback) {
        if (values.containsKey(field) && values.containsKey(legacyField)
                && booleanValue(values, field, fallback)
                != booleanValue(values, legacyField, fallback)) {
            throw invalid(field, field + " conflicts with legacy " + legacyField);
        }
    }

    private void rejectIntegerAliasConflict(Map<String, Object> values, String field,
                                            String legacyField, int fallback) {
        if (values.containsKey(field) && values.containsKey(legacyField)
                && intValue(values, field, fallback) != intValue(values, legacyField, fallback)) {
            throw invalid(field, field + " conflicts with legacy " + legacyField);
        }
    }

    private boolean booleanValue(Map<String, Object> values, String key, boolean fallback) {
        Object value = values.get(key);
        if (value == null) return fallback;
        if (value instanceof Boolean booleanValue) return booleanValue;
        throw invalid(key, key + " must be a boolean");
    }

    private int intValue(Map<String, Object> values, String key, int fallback) {
        Object value = values.get(key);
        if (value == null) return fallback;
        if (value instanceof Number number
                && number.doubleValue() == number.intValue()) return number.intValue();
        throw invalid(key, key + " must be an integer");
    }

    private Map<String, Object> generalDefaults() {
        GeneralChunkConfig config = GeneralChunkConfig.defaults();
        return Map.of(
                "delimiter", config.delimiter(),
                "delimiterMode", config.delimiterMode(),
                "maxCharacters", config.maxCharacters(),
                "collapseWhitespace", config.collapseWhitespace(),
                "removeUrls", config.removeUrls(),
                "removeEmails", config.removeEmails());
    }

    private Map<String, Object> markdownDefaults() {
        ChunkPolicy config = ChunkPolicy.defaults();
        return Map.of("minTokens", config.minTokens(),
                "targetTokens", config.targetTokens(), "maxTokens", config.maxTokens());
    }

    private ChunkingException invalid(String field, String message) {
        return ChunkingException.unprocessable("Invalid chunk strategy configuration", Map.of(
                "code", "INVALID_STRATEGY_CONFIG",
                "fieldErrors", Map.of(field, message)));
    }

    private String fieldFrom(RuntimeException exception) {
        String message = rootMessage(exception);
        for (String field : List.of("delimiterMode", "delimiter", "maxCharacters",
                "minTokens", "targetTokens", "maxTokens", "enabled", "limit")) {
            if (message.startsWith(field + ":") || message.contains("[\"" + field + "\"]")) {
                return field;
            }
        }
        return "strategyConfig";
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? "invalid value" : current.getMessage();
    }

    static String normalizeFileType(String fileType) {
        String normalized = Objects.requireNonNull(fileType, "fileType").trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith(".") ? normalized.substring(1) : normalized;
    }

    private static String normalizeCode(String code) {
        return Objects.requireNonNull(code, "code").trim().toUpperCase(Locale.ROOT);
    }

    private void validateNoDuplicateRegistrations() {
        Set<StrategyRegistration> registrations = new HashSet<>();
        for (ChunkPlanningStrategy strategy : strategies) {
            String code = normalizeCode(strategy.code());
            if (isGlobal(strategy.descriptor())) {
                if (registrations.stream().anyMatch(registration -> registration.code().equals(code))) {
                    throw new IllegalArgumentException("Duplicate chunk strategy registration: " + code);
                }
                registrations.add(new StrategyRegistration(code, "*"));
                continue;
            }
            for (String fileType : strategy.supportedFileTypes()) {
                StrategyRegistration registration = new StrategyRegistration(code, normalizeFileType(fileType));
                if (registrations.contains(new StrategyRegistration(code, "*"))
                        || !registrations.add(registration)) {
                    throw new IllegalArgumentException("Duplicate chunk strategy registration: " + registration);
                }
            }
        }
    }

    private static boolean isGlobal(ChunkStrategyDescriptor descriptor) {
        return descriptor != null && "GLOBAL".equalsIgnoreCase(descriptor.scope());
    }

    private record StrategyRegistration(String code, String fileType) {
    }
}
