package com.starsea.ai.chunking.registry;

import com.starsea.ai.chunking.spi.ChunkPlanningStrategy;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Looks up only concrete strategies registered in the Spring application context. */
@Component
public final class ChunkStrategyRegistry {

    private final List<ChunkPlanningStrategy> strategies;

    public ChunkStrategyRegistry(List<ChunkPlanningStrategy> strategies) {
        this.strategies = List.copyOf(strategies);
        validateNoDuplicateRegistrations();
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

    private void validateNoDuplicateRegistrations() {
        Set<StrategyRegistration> registrations = new HashSet<>();
        for (ChunkPlanningStrategy strategy : strategies) {
            String code = normalizeCode(strategy.code());
            for (String fileType : strategy.supportedFileTypes()) {
                StrategyRegistration registration = new StrategyRegistration(code, normalizeFileType(fileType));
                if (!registrations.add(registration)) {
                    throw new IllegalArgumentException("Duplicate chunk strategy registration: " + registration);
                }
            }
        }
    }

    private record StrategyRegistration(String code, String fileType) {
    }
}
