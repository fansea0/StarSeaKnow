# Database migrations

The backend runs Flyway migrations from `classpath:db` at startup. Migration
history is stored in `flyway_schema_history`; do not edit an applied migration
file, and do not delete that history table.

For a fresh database, Flyway applies `V1__init_auth_and_tenant.sql` followed by
`V2__invitation_based_tenant_onboarding.sql`. V1 now creates the base
application tables it requires, so no separate `rag.sql` bootstrap is needed.

For an existing V1 deployment that predates Flyway, `baseline-on-migrate` records
version 1 and applies V2 exactly once. V2 first checks that `app_user.username`
values are globally unique. If duplicates exist, startup stops before changing
the schema or any login name. Resolve duplicate usernames deliberately, then
restart the backend.

Before deployment, back up the database and run the backend once in a controlled
environment. Confirm that `flyway_schema_history` records successful versions 1
and 2. Subsequent starts validate the same checksums and run no migrations.
