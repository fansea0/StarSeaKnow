package com.starsea.ai.chunking.general;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnicodeTextTest {

    @Test
    void ascii_index_uses_direct_offsets_without_allocating_a_table() {
        UnicodeText.CodePointIndex index = UnicodeText.index("a".repeat(10_000_000));

        assertEquals(10_000_000, index.length());
        assertEquals(0, index.offsetTableArrayCount());
        assertEquals(0, index.offsetTableLength());
        assertEquals(9_999_999, index.charIndex(9_999_999));
    }

    @Test
    void supplementary_index_allocates_one_exact_sized_offset_table() {
        UnicodeText.CodePointIndex index = UnicodeText.index("A\uD83D\uDE00B\uD835\uDFD8");

        assertEquals(4, index.length());
        assertEquals(1, index.offsetTableArrayCount());
        assertEquals(index.length() + 1, index.offsetTableLength());
        assertEquals(0, index.charIndex(0));
        assertEquals(1, index.charIndex(1));
        assertEquals(3, index.charIndex(2));
        assertEquals(4, index.charIndex(3));
        assertEquals(6, index.charIndex(4));
    }
}
