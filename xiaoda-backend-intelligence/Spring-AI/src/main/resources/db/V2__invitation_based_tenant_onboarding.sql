ALTER TABLE platform_admin
    ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE tenant
    ADD COLUMN IF NOT EXISTS remark VARCHAR(512);

ALTER TABLE app_user
    DROP CONSTRAINT IF EXISTS app_user_tenant_id_username_key;

-- Tenant-scoped usernames were allowed before this migration. Retain the first
-- occurrence and make legacy duplicates globally addressable before enforcing
-- the new login identifier constraint.
WITH duplicate_usernames AS (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY username ORDER BY id) AS occurrence
    FROM app_user
)
UPDATE app_user
SET username = LEFT(username, 48) || '-' || app_user.id
FROM duplicate_usernames
WHERE app_user.id = duplicate_usernames.id
  AND duplicate_usernames.occurrence > 1;

CREATE UNIQUE INDEX IF NOT EXISTS uq_app_user_username ON app_user(username);

CREATE TABLE IF NOT EXISTS platform_invitation (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL,
    valid_from TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    used_tenant_id BIGINT REFERENCES tenant(id),
    used_user_id BIGINT REFERENCES app_user(id),
    created_by BIGINT NOT NULL REFERENCES platform_admin(id),
    create_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (status IN ('ACTIVE', 'DISABLED', 'USED')),
    CHECK (valid_until > valid_from)
);
