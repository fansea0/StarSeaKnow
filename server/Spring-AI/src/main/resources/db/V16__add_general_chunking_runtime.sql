ALTER TABLE document_chunk
    RENAME COLUMN overlap_token_limit TO overlap_limit;

ALTER TABLE document_chunk
    DROP CONSTRAINT chk_document_chunk_overlap_token_limit;

ALTER TABLE document_chunk
    ADD COLUMN overlap_unit VARCHAR(16) NOT NULL DEFAULT 'TOKENS',
    ADD COLUMN overlap_character_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN overlap_reduction_reason VARCHAR(64);

UPDATE document_chunk
SET overlap_character_count = char_length(COALESCE(overlap_content, ''));

ALTER TABLE document_chunk
    ADD CONSTRAINT chk_document_chunk_overlap_unit
        CHECK (overlap_unit IN ('TOKENS', 'CHARACTERS')),
    ADD CONSTRAINT chk_document_chunk_overlap_limit
        CHECK ((overlap_unit = 'TOKENS' AND overlap_limit BETWEEN 0 AND 512)
            OR (overlap_unit = 'CHARACTERS' AND overlap_limit BETWEEN 0 AND 1000)),
    ADD CONSTRAINT chk_document_chunk_overlap_enabled_limit
        CHECK (NOT overlap_enabled OR overlap_limit > 0),
    ADD CONSTRAINT chk_document_chunk_overlap_character_count
        CHECK (overlap_character_count >= 0);

ALTER TABLE file_processing
    ADD COLUMN execution_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN preview_summary JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN document_chunk.overlap_limit IS
    'User-editable per-chunk overlap limit measured in overlap_unit';
COMMENT ON COLUMN document_chunk.overlap_unit IS
    'Unit for overlap_limit: TOKENS or CHARACTERS';
COMMENT ON COLUMN document_chunk.overlap_character_count IS
    'Unicode character count of the persisted actual overlap content';
COMMENT ON COLUMN document_chunk.overlap_reduction_reason IS
    'Reason the actual overlap is shorter than the configured limit';
COMMENT ON COLUMN file_processing.execution_metadata IS
    'Read-only extractor and tokenizer metadata captured for reproducible execution';
COMMENT ON COLUMN file_processing.preview_summary IS
    'Persisted summary produced by the successful preview run';
