package com.starsea.ai.chunking.general;

public record CleaningStats(
        int urlMatches,
        int urlCharactersReplaced,
        int emailMatches,
        int emailCharactersReplaced,
        int whitespaceMatches,
        int whitespaceCharactersRemoved,
        int controlCharactersRemoved,
        int emptySegmentsRemoved) {
}
