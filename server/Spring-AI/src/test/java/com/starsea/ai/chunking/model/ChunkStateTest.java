package com.starsea.ai.chunking.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChunkStateTest {

    @Test
    void pipeline_codes_are_stable() {
        assertEquals(PipelineState.UPLOADED, PipelineState.fromCode(0));
        assertEquals(PipelineState.FAILED, PipelineState.fromCode(7));
        assertThrows(IllegalArgumentException.class, () -> PipelineState.fromCode(8));
    }

    @Test
    void chunk_codes_are_stable() {
        assertEquals(ChunkStatus.DRAFT, ChunkStatus.fromCode(0));
        assertEquals(ChunkStatus.ACTIVE, ChunkStatus.fromCode(2));
        assertThrows(IllegalArgumentException.class, () -> ChunkStatus.fromCode(-1));
    }
}
