ALTER TABLE document_chunk
    ADD COLUMN vector_id UUID,
    ADD COLUMN indexing_lock_version INTEGER;

UPDATE document_chunk
SET vector_id = public_id
WHERE status = 2;

UPDATE document_chunk dc
SET indexing_lock_version = fp.lock_version
FROM file_processing fp
WHERE dc.file_id = fp.file_id
  AND dc.status = 1;

ALTER TABLE document_chunk
    ADD CONSTRAINT chk_document_chunk_indexing_lock_version
        CHECK (indexing_lock_version IS NULL OR indexing_lock_version >= 0);

CREATE UNIQUE INDEX uk_document_chunk_vector_id
    ON document_chunk(vector_id)
    WHERE vector_id IS NOT NULL;

COMMENT ON COLUMN document_chunk.vector_id IS
    'Physical vector-store document ID of the currently active chunk generation';
COMMENT ON COLUMN document_chunk.indexing_lock_version IS
    'file_processing lock_version that owns the current INDEXING attempt';
