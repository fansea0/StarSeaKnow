package com.starsea.ai.chunking.context;

import com.starsea.ai.chunking.spi.TokenCounter;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultChunkContextEnricherSpringContextTest {

    @Test
    void springCanConstructComponentWithTheTokenCounterBean() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(TokenCounter.class, TestTokenCounter::new);
            context.register(DefaultChunkContextEnricher.class);

            context.refresh();

            assertThat(context.getBean(DefaultChunkContextEnricher.class)).isNotNull();
        }
    }

    private static final class TestTokenCounter implements TokenCounter {
        @Override
        public int count(String text) {
            return text == null ? 0 : text.length();
        }

        @Override
        public String id() {
            return "test";
        }
    }
}
