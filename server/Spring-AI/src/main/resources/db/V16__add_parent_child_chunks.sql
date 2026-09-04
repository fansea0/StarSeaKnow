ALTER TABLE document_chunk
    ADD COLUMN chunk_type SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN parent_chunk_id BIGINT,
    ADD COLUMN sibling_position INTEGER NOT NULL DEFAULT 0;

UPDATE document_chunk
SET chunk_type = 0,
    parent_chunk_id = NULL,
    sibling_position = position;

ALTER TABLE document_chunk
    ADD CONSTRAINT fk_document_chunk_parent
        FOREIGN KEY (parent_chunk_id) REFERENCES document_chunk(id) ON DELETE CASCADE,
    ADD CONSTRAINT chk_document_chunk_type
        CHECK (chunk_type IN (0, 1, 2)),
    ADD CONSTRAINT chk_document_chunk_hierarchy
        CHECK ((chunk_type = 2 AND parent_chunk_id IS NOT NULL)
            OR (chunk_type IN (0, 1) AND parent_chunk_id IS NULL));

CREATE INDEX idx_document_chunk_parent
    ON document_chunk(tenant_id, knowledge_id, file_id, parent_chunk_id);

CREATE UNIQUE INDEX uk_document_chunk_child_sibling
    ON document_chunk(parent_chunk_id, sibling_position)
    WHERE chunk_type = 2;
