-- =====================================================================
-- Least-privilege DB roles for the todo application
-- Security audit 2026-06-30, fix #1 (P1, class 5): remove Postgres
-- SUPERUSER from the app's runtime AND migration connections.
--
--   todouser       -> runtime datasource (DML-only: SELECT/INSERT/UPDATE/DELETE)
--   todo_migrator  -> Liquibase only (owns the todo objects, can DDL).
--                     NOT a cluster superuser: cannot touch other databases
--                     (vpscan / clickmebattle), cannot COPY ... TO PROGRAM,
--                     cannot CREATEROLE/CREATEDB/BYPASSRLS.
--
-- Run as a privileged role (postgres) AGAINST THE `todo` DATABASE ONLY.
-- Passwords are read from ENV VARS (not argv -> not visible in `ps`/history):
--   read -rs -p "migrator pw: " MIGRATOR_PW; echo
--   read -rs -p "app pw: "      APP_PW;      echo
--   export MIGRATOR_PW APP_PW
--   docker exec -e MIGRATOR_PW -e APP_PW -i postgres-db \
--     psql -U postgres -d todo -f - < db/roles/least-privilege-roles.sql
--   unset MIGRATOR_PW APP_PW
--
-- The whole script runs in ONE transaction: on any error nothing is committed
-- (no half-provisioned state). Ownership is transferred per-object because
-- `REASSIGN OWNED BY postgres` is rejected by Postgres for the bootstrap
-- superuser ("required by the database system"). Only public-schema objects
-- owned by `postgres` in THIS (`todo`) database are moved — vpscan/clickmebattle
-- live in their own databases and are not reachable from here.
-- Idempotent: safe to re-run.
-- =====================================================================

\set ON_ERROR_STOP on

-- Passwords from env (fail fast if missing — before any mutation).
\getenv migrator_pw MIGRATOR_PW
\getenv app_pw APP_PW
\if :{?migrator_pw}
\else
  \warn 'MIGRATOR_PW env var is required (set it and re-run)'
  \quit
\endif
\if :{?app_pw}
\else
  \warn 'APP_PW env var is required (set it and re-run)'
  \quit
\endif

BEGIN;

-- 1. Create roles if absent (password set below, with server-logging muted).
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'todo_migrator') THEN
    CREATE ROLE todo_migrator LOGIN;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'todouser') THEN
    CREATE ROLE todouser LOGIN;
  END IF;
END$$;

-- 2. Set/rotate passwords. SET LOCAL mutes log_statement for the rest of this
--    transaction so the cleartext literal never lands in the server log
--    (CWE-532) on clusters with log_statement = ddl|all.
SET LOCAL log_statement = 'none';
ALTER ROLE todo_migrator WITH PASSWORD :'migrator_pw';
ALTER ROLE todouser      WITH PASSWORD :'app_pw';

-- 3. Connect + schema usage.
GRANT CONNECT ON DATABASE todo TO todo_migrator, todouser;
GRANT USAGE ON SCHEMA public TO todo_migrator, todouser;
GRANT CREATE ON SCHEMA public TO todo_migrator;   -- Liquibase creates new tables

-- 4. Hand ownership of existing todo objects to todo_migrator so future
--    Liquibase changesets can ALTER/DROP them. Per-object (REASSIGN OWNED BY
--    postgres is rejected for the bootstrap superuser). Filtered to objects
--    still owned by postgres -> idempotent. Indexes follow their table.
DO $$
DECLARE r record;
BEGIN
  FOR r IN SELECT tablename FROM pg_tables
           WHERE schemaname = 'public' AND tableowner = 'postgres'
  LOOP EXECUTE format('ALTER TABLE public.%I OWNER TO todo_migrator', r.tablename); END LOOP;

  FOR r IN SELECT sequencename FROM pg_sequences
           WHERE schemaname = 'public' AND sequenceowner = 'postgres'
  LOOP EXECUTE format('ALTER SEQUENCE public.%I OWNER TO todo_migrator', r.sequencename); END LOOP;

  FOR r IN SELECT viewname FROM pg_views
           WHERE schemaname = 'public' AND viewowner = 'postgres'
  LOOP EXECUTE format('ALTER VIEW public.%I OWNER TO todo_migrator', r.viewname); END LOOP;
END$$;

-- 5. Runtime DML grants on current objects.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO todouser;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO todouser;

-- 5a. Runtime role has no business with Liquibase bookkeeping — revoke if present.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'databasechangelog') THEN
    EXECUTE 'REVOKE ALL ON databasechangelog, databasechangeloglock FROM todouser';
  END IF;
END$$;

-- 6. Default privileges: new objects created by todo_migrator (future Liquibase
--    tables/sequences) auto-grant DML to todouser — no manual GRANT per release.
ALTER DEFAULT PRIVILEGES FOR ROLE todo_migrator IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO todouser;
ALTER DEFAULT PRIVILEGES FOR ROLE todo_migrator IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO todouser;

COMMIT;

-- 7. Sanity echo (outside the transaction).
\echo 'Roles after setup (todo_migrator/todouser must be rolsuper=f):'
SELECT rolname, rolsuper, rolcreatedb, rolcreaterole, rolbypassrls
FROM pg_roles WHERE rolname IN ('postgres','todo_migrator','todouser')
ORDER BY rolsuper DESC, rolname;
