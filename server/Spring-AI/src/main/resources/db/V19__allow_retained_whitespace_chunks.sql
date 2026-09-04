ALTER TABLE document_chunk
    DROP CONSTRAINT IF EXISTS document_chunk_content_check;

ALTER TABLE document_chunk
    ADD CONSTRAINT document_chunk_content_check CHECK (length(content) > 0);
