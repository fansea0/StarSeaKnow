CREATE TABLE file_text_extraction (
    tenant_id BIGINT NOT NULL,
    file_id BIGINT PRIMARY KEY,
    source_hash VARCHAR(64) NOT NULL CHECK (char_length(source_hash) = 64),
    extractor_id VARCHAR(128) NOT NULL,
    extractor_version VARCHAR(64) NOT NULL,
    media_type VARCHAR(255) NOT NULL,
    managed_text_path TEXT NOT NULL,
    source_map_path TEXT NOT NULL,
    character_count BIGINT NOT NULL CHECK (character_count >= 0),
    create_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_file_text_extraction_tenant_file UNIQUE (tenant_id, file_id),
    CONSTRAINT fk_file_text_extraction_file
        FOREIGN KEY (file_id, tenant_id) REFERENCES file(id, tenant_id) ON DELETE CASCADE
);

CREATE INDEX idx_file_text_extraction_tenant ON file_text_extraction(tenant_id);

CREATE TRIGGER trigger_update_file_text_extraction_timestamp
    BEFORE UPDATE ON file_text_extraction
    FOR EACH ROW EXECUTE FUNCTION update_timestamp();

COMMENT ON TABLE file_text_extraction IS
    'Current managed text extraction cache for one tenant-scoped file';
