package com.starsea.ai.chunking.token;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import com.starsea.ai.chunking.config.ChunkingConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HuggingFaceTokenCounterTest {

    @Test
    void bge_tokenizer_counts_special_tokens() throws IOException {
        try (HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.newInstance(
                new ClassPathResource("tokenizer/bge-base-zh-v1.5-tokenizer.json").getInputStream(), Map.of());
             HuggingFaceTokenCounter counter = new HuggingFaceTokenCounter(
                     tokenizer, "BAAI/bge-base-zh-v1.5@7dfbf196")) {
            assertEquals(8, counter.count("湖南科技大学"));
            assertTrue(counter.id().startsWith("BAAI/bge-base-zh-v1.5@"));
        }
    }

    @Test
    void rejects_a_missing_or_modified_tokenizer_resource() {
        ChunkingConfiguration configuration = new ChunkingConfiguration();

        assertThrows(IllegalStateException.class,
                () -> configuration.bgeTokenCounter("tokenizer/missing.json", "7dfbf196"));
        assertThrows(IllegalStateException.class,
                () -> configuration.bgeTokenCounter("tokenizer/bge-base-zh-v1.5-tokenizer.json", "not-the-sha"));
    }
}
