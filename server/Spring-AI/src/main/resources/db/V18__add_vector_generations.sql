ALTER TABLE document_chunk
    ADD COLUMN vector_id UUID,
    ADD COLUMN pending_vector_id UUID,
    ADD COLUMN indexing_lock_version INTEGER;

UPDATE document_chunk
SET vector_id = public_id
WHERE status = 2;

UPDATE document_chunk dc
SET indexing_lock_version = fp.lock_version,
    pending_vector_id = dc.public_id
FROM file_processing fp
WHERE dc.file_id = fp.file_id
  AND dc.status = 1;

UPDATE document_chunk
SET indexing_lock_version = NULL,
    pending_vector_id = NULL
WHERE status <> 1;

ALTER TABLE document_chunk
    ADD CONSTRAINT chk_document_chunk_indexing_lock_version
        CHECK (indexing_lock_version IS NULL OR indexing_lock_version >= 0),
    ADD CONSTRAINT chk_document_chunk_indexing_owner
        CHECK ((status = 1) = (indexing_lock_version IS NOT NULL)),
    ADD CONSTRAINT chk_document_chunk_pending_vector
        CHECK ((status = 1) = (pending_vector_id IS NOT NULL)),
    ADD CONSTRAINT chk_document_chunk_active_vector
        CHECK (status <> 2 OR vector_id IS NOT NULL);

CREATE UNIQUE INDEX uk_document_chunk_vector_id
    ON document_chunk(vector_id)
    WHERE vector_id IS NOT NULL;

CREATE UNIQUE INDEX uk_document_chunk_pending_vector_id
    ON document_chunk(pending_vector_id)
    WHERE pending_vector_id IS NOT NULL;

CREATE TABLE chunk_vector_cleanup (
    id BIGSERIAL PRIMARY KEY,
    vector_id UUID NOT NULL UNIQUE,
    tenant_id BIGINT NOT NULL,
    knowledge_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    chunk_public_id UUID NOT NULL,
    state SMALLINT NOT NULL DEFAULT 0 CHECK (state IN (0, 1)),
    retry_count INTEGER NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
    last_error TEXT,
    create_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_chunk_vector_cleanup_pending
    ON chunk_vector_cleanup(state, create_time);

COMMENT ON COLUMN document_chunk.vector_id IS
    'Physical vector-store document ID of the currently active chunk generation';
COMMENT ON COLUMN document_chunk.pending_vector_id IS
    'Durable physical vector ID reserved by the current INDEXING attempt before vector I/O';
COMMENT ON COLUMN document_chunk.indexing_lock_version IS
    'file_processing lock_version that owns the current INDEXING attempt';
COMMENT ON TABLE chunk_vector_cleanup IS
    'Durable outbox for superseded, failed, or abandoned physical vector generations';
