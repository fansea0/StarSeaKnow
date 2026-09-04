package com.starsea.ai.chunking.api;

import com.starsea.ai.chunking.model.ContextConfig;
import com.starsea.ai.chunking.registry.ChunkStrategyDescriptor;

import java.util.List;
import java.util.Set;

/** One registered strategy plus its capability for the selected file. */
public record StrategyCapabilityResponse(
        String code,
        String scope,
        Set<String> supportedFileTypes,
        String plannerVersion,
        List<ChunkStrategyDescriptor.ConfigField> configFields,
        List<ChunkStrategyDescriptor.ConfigField> contextConfigFields,
        ContextConfig defaultContextConfig,
        boolean available,
        String reason) {

    public StrategyCapabilityResponse(ChunkStrategyDescriptor descriptor,
                                      boolean available, String reason) {
        this(descriptor.code(), descriptor.scope(), descriptor.supportedFileTypes(),
                descriptor.plannerVersion(), descriptor.configFields(),
                descriptor.contextConfigFields(),
                descriptor.defaultContextConfig(), available, reason);
    }
}
