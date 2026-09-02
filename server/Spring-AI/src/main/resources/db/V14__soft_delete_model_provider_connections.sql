-- Keep historical agent_model foreign keys intact when a no-longer-used connection is removed.
ALTER TABLE tenant_model_provider ADD COLUMN deleted_at TIMESTAMPTZ;

DROP INDEX uk_tenant_model_provider_catalog;
CREATE UNIQUE INDEX uk_tenant_model_provider_catalog
    ON tenant_model_provider(tenant_id, catalog_provider_id)
    WHERE catalog_provider_id IS NOT NULL AND deleted_at IS NULL;

DROP INDEX uk_tenant_model_provider_custom_name;
CREATE UNIQUE INDEX uk_tenant_model_provider_custom_name
    ON tenant_model_provider(tenant_id, lower(custom_name))
    WHERE catalog_provider_id IS NULL AND deleted_at IS NULL;
