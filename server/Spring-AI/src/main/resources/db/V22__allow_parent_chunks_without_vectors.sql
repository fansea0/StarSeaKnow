ALTER TABLE document_chunk
    DROP CONSTRAINT chk_document_chunk_pending_vector,
    DROP CONSTRAINT chk_document_chunk_active_vector;

ALTER TABLE document_chunk
    ADD CONSTRAINT chk_document_chunk_pending_vector
        CHECK ((status = 1 AND chunk_type <> 1) = (pending_vector_id IS NOT NULL)),
    ADD CONSTRAINT chk_document_chunk_active_vector
        CHECK (status <> 2 OR chunk_type = 1 OR vector_id IS NOT NULL);

COMMENT ON CONSTRAINT chk_document_chunk_pending_vector ON document_chunk IS
    'Vectorizable INDEXING rows own a pending vector; parent containers never do';
COMMENT ON CONSTRAINT chk_document_chunk_active_vector ON document_chunk IS
    'ACTIVE retrieval rows own vectors; parent containers are context only';
