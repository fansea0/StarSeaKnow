ALTER TABLE document_chunk
    ADD COLUMN overlap_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN overlap_token_limit INTEGER NOT NULL DEFAULT 40;

WITH legacy AS (
    SELECT fp.file_id,
           fp.tenant_id,
           fp.knowledge_id,
           CASE
               WHEN lower(COALESCE(fp.context_policy ->> 'overlapEnabled', 'false')) = 'true'
                   THEN TRUE
               ELSE FALSE
           END AS overlap_enabled,
           CASE
               WHEN COALESCE(fp.context_policy ->> 'overlapTokens', '') ~ '^[0-9]+$'
                    AND (fp.context_policy ->> 'overlapTokens')::NUMERIC BETWEEN 1 AND 512
                   THEN (fp.context_policy ->> 'overlapTokens')::INTEGER
               ELSE 40
           END AS overlap_token_limit
    FROM file_processing fp
)
UPDATE document_chunk dc
SET overlap_enabled = legacy.overlap_enabled
                          OR NULLIF(BTRIM(dc.overlap_content), '') IS NOT NULL,
    overlap_token_limit = LEAST(512, GREATEST(
            legacy.overlap_token_limit,
            CASE
                WHEN NULLIF(BTRIM(dc.overlap_content), '') IS NOT NULL
                    THEN GREATEST(dc.overlap_token_count, 1)
                ELSE 1
            END))
FROM legacy
WHERE legacy.file_id = dc.file_id
  AND legacy.tenant_id = dc.tenant_id
  AND legacy.knowledge_id = dc.knowledge_id;

ALTER TABLE document_chunk
    ADD CONSTRAINT chk_document_chunk_overlap_token_limit
        CHECK (overlap_token_limit BETWEEN 1 AND 512);

COMMENT ON COLUMN document_chunk.overlap_enabled IS
    'Per-chunk switch for deriving read-only overlap context';
COMMENT ON COLUMN document_chunk.overlap_token_limit IS
    'User-editable per-chunk overlap token limit, retained for source-driven recalculation';
