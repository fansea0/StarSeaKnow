package com.starsea.ai.chunking.registry;

import com.starsea.ai.chunking.spi.ChunkPlanningStrategy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Looks up only concrete strategies registered in the Spring application context. */
@Component
public final class ChunkStrategyRegistry {

    private final List<ChunkPlanningStrategy> strategies;

    public ChunkStrategyRegistry(List<ChunkPlanningStrategy> strategies) {
        this.strategies = List.copyOf(strategies);
    }

    public ChunkPlanningStrategy require(String code, String fileType) {
        String normalizedCode = normalizeCode(code);
        String normalizedFileType = normalizeFileType(fileType);
        return strategies.stream()
                .filter(strategy -> normalizedCode.equals(normalizeCode(strategy.code())))
                .filter(strategy -> strategy.supportedFileTypes().stream()
                        .map(ChunkStrategyRegistry::normalizeFileType)
                        .anyMatch(normalizedFileType::equals))
                .findFirst()
                .orElseThrow(() -> new ChunkStrategyNotFoundException(code, fileType));
    }

    static String normalizeFileType(String fileType) {
        String normalized = Objects.requireNonNull(fileType, "fileType").trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith(".") ? normalized.substring(1) : normalized;
    }

    private static String normalizeCode(String code) {
        return Objects.requireNonNull(code, "code").trim().toUpperCase(Locale.ROOT);
    }
}
