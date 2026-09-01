package com.starsea.ai.chunking.markdown;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import com.starsea.ai.chunking.model.ChunkDraft;
import com.starsea.ai.chunking.model.ChunkPolicy;
import com.starsea.ai.chunking.model.FileResource;
import com.starsea.ai.chunking.model.ParsedStructure;
import com.starsea.ai.chunking.spi.TokenCounter;
import com.starsea.ai.chunking.token.HuggingFaceTokenCounter;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownGoldenSampleTest {

    @Test
    void plans_the_read_only_knowledge_base_sample_within_quality_and_budget_bounds() throws IOException {
        try (TokenCounterResource counterResource = exactCounter()) {
            TokenCounter counter = counterResource.counter();
            Path path = Path.of("src/main/resources/file/科大百事通.md").toAbsolutePath().normalize();
            FileResource resource = new FileResource(1, 2, 3,
                    UUID.fromString("00000000-0000-0000-0000-000000000003"),
                    "科大百事通.md", "md", path);
            ParsedStructure structure = new MarkdownStructureParser(counter).parse(resource);

            List<ChunkDraft> chunks = new MarkdownChunkPlanningStrategy(counter)
                    .plan(structure, ChunkPolicy.defaults());

            assertTrue(chunks.size() >= 5 && chunks.size() <= 15);
            assertTrue(chunks.stream().allMatch(c -> !c.content().isBlank()));
            assertTrue(chunks.stream().allMatch(c -> counter.count(indexText(c)) <= 512));
            assertTrue(chunks.stream().anyMatch(c -> c.sectionPath().contains("招生录取类问题")
                    && c.content().contains("Q1") && c.content().contains("一本批次")));
        }
    }

    private String indexText(ChunkDraft chunk) {
        return chunk.sectionPath().isEmpty()
                ? chunk.content()
                : "标题：" + String.join(" > ", chunk.sectionPath()) + "\n\n" + chunk.content();
    }

    private TokenCounterResource exactCounter() throws IOException {
        HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.newInstance(
                new ClassPathResource("tokenizer/bge-base-zh-v1.5-tokenizer.json").getInputStream(), Map.of());
        return new TokenCounterResource(new HuggingFaceTokenCounter(
                tokenizer, "BAAI/bge-base-zh-v1.5@7dfbf196"));
    }

    private record TokenCounterResource(HuggingFaceTokenCounter counter) implements AutoCloseable {

        @Override
        public void close() {
            counter.close();
        }
    }
}
