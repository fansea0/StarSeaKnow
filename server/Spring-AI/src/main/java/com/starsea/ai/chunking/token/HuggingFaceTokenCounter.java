package com.starsea.ai.chunking.token;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import com.starsea.ai.chunking.spi.TokenCounter;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Exact HuggingFace tokenizer adapter; the encoded sequence includes BERT special tokens. */
public final class HuggingFaceTokenCounter implements TokenCounter, AutoCloseable {

    private final HuggingFaceTokenizer tokenizer;
    private final String id;

    public HuggingFaceTokenCounter(HuggingFaceTokenizer tokenizer, String id) {
        this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
        this.id = Objects.requireNonNull(id, "id");
    }

    @Override
    public int count(String text) {
        return tokenizer.encode(text == null ? "" : text).getIds().length;
    }

    @Override
    public List<Integer> countBatch(List<String> texts) {
        Objects.requireNonNull(texts, "texts");
        String[] normalized = texts.stream().map(text -> text == null ? "" : text)
                .toArray(String[]::new);
        return Arrays.stream(tokenizer.batchEncode(normalized))
                .map(encoding -> Math.toIntExact(Arrays.stream(
                        encoding.getAttentionMask()).sum()))
                .toList();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public void close() {
        tokenizer.close();
    }
}
