package com.starsea.ai.chunking.runtime;

import com.starsea.ai.chunking.model.ChunkPolicy;
import com.starsea.ai.chunking.model.ChunkStrategyConfig;
import com.starsea.ai.chunking.model.ContextConfig;
import com.starsea.ai.chunking.model.ContextMode;
import com.starsea.ai.chunking.model.DelimiterMode;
import com.starsea.ai.chunking.model.GeneralChunkConfig;
import com.starsea.ai.chunking.model.OverlapUnit;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** The single parser for policy values read from persisted JSONB snapshots. */
@Component
public final class ChunkRuntimePolicyResolver {

    public ChunkRuntimePolicy resolve(
            String strategyCode,
            Map<String, Object> policySnapshot,
            Map<String, Object> contextPolicy,
            Map<String, Object> executionMetadata) {
        String code = Objects.requireNonNull(strategyCode, "strategyCode").trim().toUpperCase(Locale.ROOT);
        Map<String, Object> policy = safe(policySnapshot);
        Map<String, Object> context = safe(contextPolicy);
        Map<String, Object> execution = safe(executionMetadata);

        return switch (code) {
            case "MARKDOWN_OPTIMIZED" -> markdown(code, policy, context, execution);
            case "GENERAL" -> general(code, policy, context, execution);
            default -> throw new IllegalArgumentException("Unknown persisted chunk strategy: " + strategyCode);
        };
    }

    private ChunkRuntimePolicy markdown(String code, Map<String, Object> policy,
                                        Map<String, Object> context, Map<String, Object> execution) {
        ChunkPolicy defaults = ChunkPolicy.defaults();
        ChunkPolicy config = new ChunkPolicy(
                integer(policy, "minTokens", defaults.minTokens()),
                integer(policy, "targetTokens", defaults.targetTokens()),
                integer(policy, "maxTokens", defaults.maxTokens()));
        ContextConfig contextConfig = context(context, OverlapUnit.TOKENS, ContextMode.COMPLETE_SENTENCE,
                ContextConfig.markdownDefaults(), true);
        int maxIndexTokens = integer(execution, "tokenHardLimit", config.maxTokens());
        String tokenizerId = string(execution, "tokenizerId", string(policy, "tokenizer", null));
        return new ChunkRuntimePolicy(code, config, contextConfig, maxIndexTokens, tokenizerId);
    }

    private ChunkRuntimePolicy general(String code, Map<String, Object> policy,
                                       Map<String, Object> context, Map<String, Object> execution) {
        GeneralChunkConfig defaults = GeneralChunkConfig.defaults();
        ChunkStrategyConfig config = new GeneralChunkConfig(
                string(policy, "delimiter", defaults.delimiter()),
                enumValue(policy, "delimiterMode", DelimiterMode.class, defaults.delimiterMode()),
                integer(policy, "maxCharacters", defaults.maxCharacters()),
                bool(policy, "collapseWhitespace", defaults.collapseWhitespace()),
                bool(policy, "removeUrls", defaults.removeUrls()),
                bool(policy, "removeEmails", defaults.removeEmails()));
        ContextConfig contextConfig = context(context, OverlapUnit.CHARACTERS, ContextMode.CHARACTER_TAIL,
                ContextConfig.generalDefaults(), true);
        int maxIndexTokens = integer(execution, "tokenHardLimit", ChunkPolicy.MAX_ALLOWED_TOKENS);
        return new ChunkRuntimePolicy(code, config, contextConfig, maxIndexTokens,
                string(execution, "tokenizerId", null));
    }

    private ContextConfig context(Map<String, Object> values, OverlapUnit unit, ContextMode mode,
                                  ContextConfig defaults, boolean normalizeZero) {
        boolean enabled = bool(values, values.containsKey("enabled") ? "enabled" : "overlapEnabled",
                defaults.enabled());
        int limit = integer(values, values.containsKey("limit") ? "limit" : "overlapTokens",
                defaults.limit());
        if (normalizeZero && limit == 0) enabled = false;
        return new ContextConfig(enabled, limit, unit, mode);
    }

    private Map<String, Object> safe(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }

    private int integer(Map<String, Object> values, String key, int fallback) {
        Object value = values.get(key);
        if (value == null) return fallback;
        if (value instanceof Number number && number.doubleValue() == number.intValue()) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                // Report the persisted field below.
            }
        }
        throw new IllegalArgumentException("Persisted " + key + " must be an integer");
    }

    private boolean bool(Map<String, Object> values, String key, boolean fallback) {
        Object value = values.get(key);
        if (value == null) return fallback;
        if (value instanceof Boolean booleanValue) return booleanValue;
        if (value instanceof String text && ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text))) {
            return Boolean.parseBoolean(text);
        }
        throw new IllegalArgumentException("Persisted " + key + " must be a boolean");
    }

    private String string(Map<String, Object> values, String key, String fallback) {
        Object value = values.get(key);
        if (value == null) return fallback;
        if (value instanceof String text) return text;
        throw new IllegalArgumentException("Persisted " + key + " must be a string");
    }

    private <E extends Enum<E>> E enumValue(Map<String, Object> values, String key,
                                             Class<E> type, E fallback) {
        String value = string(values, key, null);
        return value == null ? fallback : Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
    }
}
