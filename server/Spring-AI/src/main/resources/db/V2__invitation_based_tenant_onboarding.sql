-- Deliberately fail before changing any schema or login name if legacy tenant
-- accounts use the same username. Resolve those records explicitly first.
SELECT COALESCE((
    SELECT ('Cannot migrate app_user to globally unique usernames; resolve duplicate username records first: ' || username)::INTEGER
    FROM app_user
    GROUP BY username
    HAVING COUNT(*) > 1
    ORDER BY username
    LIMIT 1
), 1);

ALTER TABLE platform_admin
    ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE tenant
    ADD COLUMN IF NOT EXISTS remark VARCHAR(512);

ALTER TABLE app_user
    DROP CONSTRAINT IF EXISTS app_user_tenant_id_username_key;

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
