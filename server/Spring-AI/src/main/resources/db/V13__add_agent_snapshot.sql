CREATE TABLE agent_snapshot (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenant(id) ON DELETE RESTRICT,
    agent_id BIGINT NOT NULL,
    version_number BIGINT NOT NULL CHECK (version_number >= 1),
    publish_note VARCHAR(512) NOT NULL,
    snapshot_data JSONB NOT NULL CHECK (jsonb_typeof(snapshot_data) = 'object'),
    source_revision BIGINT NOT NULL CHECK (source_revision >= 1),
    rollback_from_snapshot_id BIGINT,
    created_by BIGINT NOT NULL,
    create_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP WITH TIME ZONE,
    deleted_by BIGINT,
    CONSTRAINT uk_agent_snapshot_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT uk_agent_snapshot_identity UNIQUE (id, agent_id, tenant_id),
    CONSTRAINT uk_agent_snapshot_version UNIQUE (tenant_id, agent_id, version_number),
    CONSTRAINT fk_agent_snapshot_agent_tenant
        FOREIGN KEY (agent_id, tenant_id)
        REFERENCES agent(id, tenant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_snapshot_rollback_tenant
        FOREIGN KEY (rollback_from_snapshot_id, tenant_id)
        REFERENCES agent_snapshot(id, tenant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_snapshot_created_by_tenant
        FOREIGN KEY (created_by, tenant_id)
        REFERENCES app_user(id, tenant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_snapshot_deleted_by_tenant
        FOREIGN KEY (deleted_by, tenant_id)
        REFERENCES app_user(id, tenant_id) ON DELETE RESTRICT,
    CONSTRAINT ck_agent_snapshot_deleted_actor CHECK (
        (deleted_at IS NULL AND deleted_by IS NULL)
        OR (deleted_at IS NOT NULL AND deleted_by IS NOT NULL)
    )
);

CREATE INDEX idx_agent_snapshot_agent_version
    ON agent_snapshot(tenant_id, agent_id, version_number DESC)
    WHERE deleted_at IS NULL;

ALTER TABLE agent
    ADD CONSTRAINT fk_agent_current_snapshot_identity
        FOREIGN KEY (current_snapshot_id, id, tenant_id)
        REFERENCES agent_snapshot(id, agent_id, tenant_id) ON DELETE RESTRICT;

CREATE OR REPLACE FUNCTION prevent_agent_snapshot_mutation()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.agent_id IS DISTINCT FROM OLD.agent_id
       OR NEW.version_number IS DISTINCT FROM OLD.version_number
       OR NEW.publish_note IS DISTINCT FROM OLD.publish_note
       OR NEW.snapshot_data IS DISTINCT FROM OLD.snapshot_data
       OR NEW.source_revision IS DISTINCT FROM OLD.source_revision
       OR NEW.rollback_from_snapshot_id IS DISTINCT FROM OLD.rollback_from_snapshot_id
       OR NEW.created_by IS DISTINCT FROM OLD.created_by
       OR NEW.create_time IS DISTINCT FROM OLD.create_time THEN
        RAISE EXCEPTION 'agent snapshot content is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_prevent_agent_snapshot_mutation
    BEFORE UPDATE ON agent_snapshot
    FOR EACH ROW EXECUTE FUNCTION prevent_agent_snapshot_mutation();
