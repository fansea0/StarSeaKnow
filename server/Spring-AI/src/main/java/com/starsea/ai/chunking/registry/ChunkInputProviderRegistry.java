package com.starsea.ai.chunking.registry;

import com.starsea.ai.chunking.model.FileResource;
import com.starsea.ai.chunking.spi.ChunkInputProvider;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Routes source conversion by strategy first and file type second. */
@Component
public final class ChunkInputProviderRegistry {
    private final List<ChunkInputProvider> providers;

    public ChunkInputProviderRegistry(List<ChunkInputProvider> providers) {
        this.providers = List.copyOf(providers);
        Set<String> registrations = new HashSet<>();
        for (ChunkInputProvider provider : this.providers) {
            String code = normalize(provider.strategyCode());
            Set<String> types = provider.global() ? Set.of("*") : provider.supportedFileTypes();
            for (String type : types) {
                String key = code + "@" + normalizeType(type);
                boolean conflictsWithGlobal = registrations.contains(code + "@*")
                        || "*".equals(normalizeType(type))
                        && registrations.stream().anyMatch(existing -> existing.startsWith(code + "@"));
                if (conflictsWithGlobal || !registrations.add(key)) {
                    throw new IllegalArgumentException("Duplicate chunk input provider registration: " + key);
                }
            }
        }
    }

    public ChunkInputProvider require(String strategyCode, String fileType) {
        String code = normalize(strategyCode);
        String type = normalizeType(fileType);
        return providers.stream()
                .filter(provider -> code.equals(normalize(provider.strategyCode())))
                .filter(provider -> provider.global() || provider.supportedFileTypes().stream()
                        .map(ChunkInputProviderRegistry::normalizeType).anyMatch(type::equals))
                .findFirst()
                .orElseThrow(() -> new ChunkStrategyNotFoundException(strategyCode, fileType));
    }

    public ChunkInputProvider.Capability capability(String strategyCode, FileResource resource) {
        try {
            return require(strategyCode, resource.fileType()).capability(resource);
        } catch (ChunkStrategyNotFoundException | NullPointerException exception) {
            return ChunkInputProvider.Capability.unavailable(
                    "The strategy has no input provider for this file");
        }
    }

    private static String normalize(String value) {
        return Objects.requireNonNull(value, "strategyCode").trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeType(String value) {
        String normalized = Objects.requireNonNull(value, "fileType").trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith(".") ? normalized.substring(1) : normalized;
    }
}
