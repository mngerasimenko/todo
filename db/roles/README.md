# Least-privilege DB roles

Security audit 2026-06-30, fix #1 (P1, class 5). Removes Postgres **SUPERUSER**
from the application's runtime and migration connections. The Postgres instance
is **shared** (todo + vpscan + clickmebattle), so a superuser app connection
means any app compromise = control of every database on the instance + RCE via
`COPY ... TO PROGRAM`. This change confines the app to least privilege.

## Role model

| Role | Used by | Privileges |
|---|---|---|
| `postgres` | **Admin only** — `backup.sh`, pre-deploy `pg_dump`, manual ops | SUPERUSER (unchanged; the app no longer uses it) |
| `todo_migrator` | **Liquibase only** (`SPRING_LIQUIBASE_USER`) | Owns the `public`-schema objects in DB `todo` + `CREATE`/DDL. **Not** superuser/createrole/createdb/bypassrls. Locked to the `todo` database — cannot see vpscan/clickmebattle, cannot `COPY ... TO PROGRAM` |
| `todouser` | **Runtime datasource** (`SPRING_DATASOURCE_USERNAME`) | DML only: `SELECT/INSERT/UPDATE/DELETE` + `USAGE` on sequences. Default privileges from `todo_migrator` auto-grant DML on future tables |

The app's runtime needs only DML (verified: the only non-DML DB op is
`pg_try_advisory_xact_lock`, which any role may call; no TRUNCATE/DDL at runtime).

## Files

- `least-privilege-roles.sql` — create roles, grants, ownership, default privileges. Idempotent.
- `least-privilege-rollback.sql` — return ownership to `postgres`, drop the scoped roles.

## Environment wiring

`docker-compose.yml` / `docker-compose.staging.yml` read (safe-by-default — empty
value falls back to `postgres`/`POSTGRES_PASSWORD`, i.e. current behavior):

```
SPRING_DATASOURCE_USERNAME=${APP_DB_USER:-postgres}
SPRING_DATASOURCE_PASSWORD=${APP_DB_PASSWORD:-${POSTGRES_PASSWORD}}
SPRING_LIQUIBASE_USER=${MIGRATOR_DB_USER:-postgres}
SPRING_LIQUIBASE_PASSWORD=${MIGRATOR_DB_PASSWORD:-${POSTGRES_PASSWORD}}
```

New GitHub Secrets (prod `.env` is written from them in `deploy.yml`):
`APP_DB_USER`, `APP_DB_PASSWORD`, `MIGRATOR_DB_USER`, `MIGRATOR_DB_PASSWORD`.

## Apply runbook (production — owner-sanctioned step, G3)

> **Order matters: roles first, then secrets, then deploy.** Otherwise the app
> starts as a non-existent `todouser` and fails.
>
> **Both-or-none per pair.** Set `APP_DB_USER`+`APP_DB_PASSWORD` together and
> `MIGRATOR_DB_USER`+`MIGRATOR_DB_PASSWORD` together. Setting a user without its
> password → auth failure at boot. Setting `APP_DB_*` but leaving `MIGRATOR_DB_*`
> empty is the worst trap: Liquibase then runs as `postgres`, future tables are
> owned by `postgres`, the default-privilege rules (keyed on `todo_migrator`)
> never fire, and `todouser` silently loses access **after the next migration**.
>
> **Prereq:** this branch must be **merged to master and pulled on the host**
> first — `deploy.yml` does `git reset --hard origin/master`, and the runbook
> runs the SQL from `db/roles/` in that checkout.

1. **Provision roles** on prod-db. Pass passwords via env (not argv → not in
   `ps`/shell history; generate with `openssl rand -base64 24`):
   ```bash
   read -rs -p "todo_migrator password: " MIGRATOR_PW; echo
   read -rs -p "todouser password: "      APP_PW;      echo
   export MIGRATOR_PW APP_PW
   docker exec -e MIGRATOR_PW -e APP_PW -i postgres-db \
     psql -U postgres -d todo -f - < db/roles/least-privilege-roles.sql
   unset MIGRATOR_PW APP_PW
   ```
   Runs in one transaction (no half-state on error). It prints the role table at
   the end — confirm `todouser`/`todo_migrator` have `rolsuper=f`.
2. **GitHub Secrets:** add `APP_DB_USER=todouser`, `APP_DB_PASSWORD=<APP_PW>`,
   `MIGRATOR_DB_USER=todo_migrator`, `MIGRATOR_DB_PASSWORD=<MIGRATOR_PW>`.
3. **Deploy:** `gh workflow run deploy.yml -f deploy_target=production`.
4. **Smoke:** app `healthy` + Liquibase ran without `permission denied`;
   `GET /api/status` → 200; login + create/delete task (write path as `todouser`);
   `SELECT rolname,rolsuper FROM pg_roles WHERE rolname IN ('todouser','todo_migrator')` → both `f`.

## Rollback

1. Clear/remove the 4 secrets → redeploy → `.env` no longer has them → compose
   falls back to `postgres`. App runs as superuser again (working state).
2. Optionally drop the roles: `psql ... -f - < db/roles/least-privilege-rollback.sql`.

## Backup / DR portability

After ownership moves to `todo_migrator`, `pg_dump` embeds `ALTER … OWNER TO
todo_migrator`. `backup.sh` / offsite / pre-deploy dumps keep working (they run as
`postgres`). But **restoring to a fresh instance** (DR box, rebuilt prod) where
the role doesn't exist yet will error on every owner line — first create the roles
there (run `least-privilege-roles.sql`), **or** restore with `pg_restore --no-owner`.

## Staging-first

`staging-db` is a separate instance — run the same script there, add the env vars
to the persistent staging `.env`, recreate `staging-app`, smoke. `backup.sh` /
offsite keep using `postgres` (admin role) and are untouched.
