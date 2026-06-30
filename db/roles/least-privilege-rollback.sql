-- =====================================================================
-- ROLLBACK for least-privilege-roles.sql
-- Returns todo objects to `postgres` ownership and drops the scoped roles.
-- Run as postgres against the `todo` database:
--   docker exec -i postgres-db psql -U postgres -d todo \
--     -f - < db/roles/least-privilege-rollback.sql
--
-- AFTER running this, revert the app connection in the host .env:
--   APP_DB_USER / APP_DB_PASSWORD / MIGRATOR_DB_USER / MIGRATOR_DB_PASSWORD
--   removed (or emptied) -> docker-compose falls back to postgres/POSTGRES_PASSWORD
--   -> recreate todo-app container.
--
-- REASSIGN OWNED BY todo_migrator works (todo_migrator is NOT the bootstrap
-- superuser, unlike `postgres`). Ownership transfer + grant cleanup run in one
-- transaction; the roles are dropped after it commits.
-- =====================================================================

\set ON_ERROR_STOP on

BEGIN;

-- 1. Return ownership of all todo objects to postgres (data preserved).
REASSIGN OWNED BY todo_migrator TO postgres;

-- 2. Drop grants + default-privilege rules held by / granted to the scoped roles.
--    (DROP OWNED removes ACL entries; todouser owns no objects, only ACLs.)
DROP OWNED BY todouser;
DROP OWNED BY todo_migrator;

COMMIT;

-- 3. Drop the roles (now they own nothing and hold no grants).
DROP ROLE IF EXISTS todouser;
DROP ROLE IF EXISTS todo_migrator;

\echo 'Roles after rollback (todo_migrator/todouser should be gone):'
SELECT rolname FROM pg_roles WHERE rolname IN ('postgres','todo_migrator','todouser') ORDER BY rolname;
