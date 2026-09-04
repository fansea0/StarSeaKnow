package com.starsea.ai.chunking.registry;

import com.starsea.ai.chunking.model.ContextConfig;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Metadata exposed for a concrete, registered chunk-planning strategy. */
public record ChunkStrategyDescriptor(
        String code,
        String scope,
        Set<String> supportedFileTypes,
        String plannerVersion,
        List<ConfigField> configFields,
        List<ConfigField> contextConfigFields,
        ContextConfig defaultContextConfig) {

    public ChunkStrategyDescriptor {
        supportedFileTypes = supportedFileTypes == null ? Set.of() : Set.copyOf(supportedFileTypes);
        configFields = configFields == null ? List.of() : List.copyOf(configFields);
        defaultContextConfig = defaultContextConfig == null
                ? ContextConfig.markdownDefaults() : defaultContextConfig;
        contextConfigFields = contextConfigFields == null
                ? contextConfigFields(defaultContextConfig) : List.copyOf(contextConfigFields);
    }

    public ChunkStrategyDescriptor(String code, String scope, Set<String> supportedFileTypes,
                                   String plannerVersion, List<ConfigField> configFields,
                                   ContextConfig defaultContextConfig) {
        this(code, scope, supportedFileTypes, plannerVersion, configFields,
                contextConfigFields(defaultContextConfig), defaultContextConfig);
    }

    public ChunkStrategyDescriptor(String code, String scope, Set<String> supportedFileTypes,
                                   String plannerVersion, List<ConfigField> configFields) {
        this(code, scope, supportedFileTypes, plannerVersion, configFields,
                contextConfigFields(ContextConfig.markdownDefaults()), ContextConfig.markdownDefaults());
    }

    public ChunkStrategyDescriptor(String code, Set<String> supportedFileTypes, String plannerVersion) {
        this(code, "FILE_TYPE", supportedFileTypes, plannerVersion, List.of(),
                contextConfigFields(ContextConfig.markdownDefaults()), ContextConfig.markdownDefaults());
    }

    public static List<ConfigField> contextConfigFields(ContextConfig defaults) {
        ContextConfig value = defaults == null ? ContextConfig.markdownDefaults() : defaults;
        return List.of(
                new ConfigField("enabled", "boolean", value.enabled(), null, null, Map.of()),
                new ConfigField("limit", "number", value.limit(), ContextConfig.MIN_LIMIT,
                        ContextConfig.maximumLimit(value.unit()),
                        Map.of("unit", value.unit().name(), "mode", value.mode().name())));
    }

    public record ConfigField(String key, String type, Object defaultValue, Integer min, Integer max,
                              Map<String, Object> attributes) {
        public ConfigField {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }
}
