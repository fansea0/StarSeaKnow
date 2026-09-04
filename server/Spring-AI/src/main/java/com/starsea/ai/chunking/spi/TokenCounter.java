package com.starsea.ai.chunking.spi;

import java.util.List;

/** Counts tokens using the exact tokenizer used for chunk-budget enforcement. */
public interface TokenCounter {

    int count(String text);

    /** Counts an ordered batch exactly; specialized tokenizers should encode it in one call. */
    default List<Integer> countBatch(List<String> texts) {
        return texts.stream().map(this::count).toList();
    }

    String id();
}
