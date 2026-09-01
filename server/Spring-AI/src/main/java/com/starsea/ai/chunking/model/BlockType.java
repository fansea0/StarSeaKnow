package com.starsea.ai.chunking.model;

/** Structural block kinds emitted by document parsers. */
public enum BlockType {
    HEADING,
    PARAGRAPH,
    ORDERED_LIST,
    UNORDERED_LIST,
    BLOCK_QUOTE,
    FENCED_CODE,
    INDENTED_CODE,
    TABLE,
    THEMATIC_BREAK,
    HTML_BLOCK
}
