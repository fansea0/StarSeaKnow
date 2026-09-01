package com.starsea.ai.chunking.spi;

/** Counts tokens using the exact tokenizer used for chunk-budget enforcement. */
public interface TokenCounter {

    int count(String text);

    String id();
}
