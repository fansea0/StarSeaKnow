package com.starsea.ai.chunking.context;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChunkIndexContentBuilderTest {

    @Test
    void renders_title_and_edited_body_without_overlap() {
        String result = new ChunkIndexContentBuilder().build(
                List.of("招生录取类问题"), null, "编辑后的正文");

        assertEquals("标题：招生录取类问题\n\n编辑后的正文", result);
    }

    @Test
    void renders_overlap_between_title_and_current_edited_body() {
        String result = new ChunkIndexContentBuilder().build(
                List.of("招生录取类问题"), "这是前一个 Chunk 的完整句。", "编辑后的正文");

        assertEquals("标题：招生录取类问题\n上文：这是前一个 Chunk 的完整句。\n\n编辑后的正文", result);
    }
}
