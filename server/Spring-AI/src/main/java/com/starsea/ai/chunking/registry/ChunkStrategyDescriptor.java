package com.starsea.ai.chunking.registry;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Metadata exposed for a concrete, registered chunk-planning strategy. */
public record ChunkStrategyDescriptor(
        String code,
        String scope,
        Set<String> supportedFileTypes,
        String plannerVersion,
        List<ConfigField> configFields) {

    public ChunkStrategyDescriptor {
        supportedFileTypes = supportedFileTypes == null ? Set.of() : Set.copyOf(supportedFileTypes);
        configFields = configFields == null ? List.of() : List.copyOf(configFields);
    }

    public ChunkStrategyDescriptor(String code, Set<String> supportedFileTypes, String plannerVersion) {
        this(code, "FILE_TYPE", supportedFileTypes, plannerVersion, List.of());
    }

    public record ConfigField(String key, String type, Object defaultValue, Integer min, Integer max,
                              Map<String, Object> attributes) {
        public ConfigField {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }
}
