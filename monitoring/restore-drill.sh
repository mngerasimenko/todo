#!/bin/bash
# PostgreSQL restore drill: verify the latest backup actually RESTORES.
#
# backup.sh / offsite-backup.sh only check `[ -s file ]` (dump merely non-empty).
# A corrupt-but-non-empty dump would pass that check and stay unnoticed until a
# real disaster. This drill restores the newest -Fc dump into a THROWAWAY database
# in the same postgres-db container and asserts the schema restored fully (table
# count), core tables are queryable, and the users table is non-empty — proving the
# dump is genuinely restorable.
#
# Isolation & safety:
#   - The throwaway DB is a separate database (todo_restore_drill) in the same
#     instance, dropped on exit via trap. The live `todo` database is never touched.
#   - A guard refuses to run if DRILL_DB is ever pointed at a real database.
#   - The dump is copied INTO the container and restored from a file path (not piped
#     via stdin), so it works regardless of the container's PostgreSQL version.
#   - --no-owner --no-privileges keeps the restore independent of the least-privilege
#     roles. If the DB uses extensions, the restoring superuser (postgres) recreates
#     them (createdb -U postgres grants CREATE).
#
# Runbook (owner — runs on the PRODUCTION host, where postgres-db and
# /root/backups live; G3, agent does not touch prod):
#   1. Copy alongside the other monitoring scripts: /root/monitoring/restore-drill.sh
#   2. chmod +x /root/monitoring/restore-drill.sh
#   3. monitor.conf must have TELEGRAM_BOT_TOKEN + TELEGRAM_CHAT_ID (same as backup.sh)
#   4. Crontab — weekly, 30 min after the 03:00 daily backup:
#        30 3 * * 1 /root/monitoring/restore-drill.sh >> /root/backups/restore-drill.log 2>&1
#   5. Verify once manually: /root/monitoring/restore-drill.sh ; echo "exit=$?"
#
# Exit 0 = drill passed; exit 1 = failure (Telegram alert sent).

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKUP_DIR="${BACKUP_DIR:-/root/backups}"
CONTAINER="${CONTAINER:-postgres-db}"
DB_USER="${DB_USER:-postgres}"
DRILL_DB="${DRILL_DB:-todo_restore_drill}"
# Tables whose presence proves the schema restored (must be queryable)
ASSERT_TABLES="${ASSERT_TABLES:-todo_users task_list todo task_list_user refresh_token}"
# Table that must contain rows — proves data (not just schema) restored.
# todo_users always holds at least the system "Deleted user" (id=0) plus real users.
NONEMPTY_TABLE="${NONEMPTY_TABLE:-todo_users}"
# Minimum public-schema tables expected after a full restore (8 entities + 2 Liquibase).
# Guards against a partial restore that populates a couple of tables and skips the rest.
MIN_TABLES="${MIN_TABLES:-8}"

# Safety guard: never let the drill operate on a real database, even via env override.
case "$DRILL_DB" in
    todo|postgres|template0|template1)
        echo "FATAL: DRILL_DB='$DRILL_DB' is a protected database name — refusing to run" >&2
        exit 1 ;;
esac

# Unique dump path INSIDE the container (container /tmp, separate from host /tmp)
IN_CONTAINER_DUMP="/tmp/restore-drill-$$.dump"
ERR_FILE="$(mktemp)"

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*"; }

send_telegram_alert() {
    if [ -f "${SCRIPT_DIR}/monitor.conf" ]; then
        # shellcheck source=/dev/null
        source "${SCRIPT_DIR}/monitor.conf"
    fi
    # Guard both vars (set -u would otherwise abort here when monitor.conf omits them).
    if [ -n "${TELEGRAM_BOT_TOKEN:-}" ] && [ -n "${TELEGRAM_CHAT_ID:-}" ]; then
        curl -s -X POST "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage" \
            -d chat_id="${TELEGRAM_CHAT_ID}" \
            -d parse_mode="HTML" \
            -d text="$1" > /dev/null 2>&1
    fi
}

fail() {
    log "ERROR: $1" >&2
    send_telegram_alert "🔴 <b>Restore-drill failed!</b>
📅 $(date '+%Y-%m-%d %H:%M:%S')
Host: $(hostname)
$1"
    exit 1
}

# Always drop the throwaway DB and remove the in-container dump on exit
cleanup() {
    docker exec "$CONTAINER" dropdb -U "$DB_USER" --if-exists "$DRILL_DB" >/dev/null 2>&1
    docker exec "$CONTAINER" rm -f "$IN_CONTAINER_DUMP" >/dev/null 2>&1
    rm -f "$ERR_FILE"
}
trap cleanup EXIT

# 1. Pick the newest dump
LATEST_DUMP="$(ls -1t "$BACKUP_DIR"/todo-*.dump 2>/dev/null | head -n1)"
[ -n "$LATEST_DUMP" ] || fail "No backup dump found in $BACKUP_DIR (todo-*.dump)"
[ -s "$LATEST_DUMP" ] || fail "Latest dump is empty: $LATEST_DUMP"
log "Restore-drill target: $LATEST_DUMP ($(du -h "$LATEST_DUMP" | cut -f1))"

# 2. Copy the dump into the container (seekable file → version-independent restore)
docker cp "$LATEST_DUMP" "${CONTAINER}:${IN_CONTAINER_DUMP}" \
    || fail "Could not copy dump into container $CONTAINER"

# 3. Fresh throwaway DB (drop leftovers from a prior aborted run, then create)
docker exec "$CONTAINER" dropdb -U "$DB_USER" --if-exists "$DRILL_DB" >/dev/null 2>&1
docker exec "$CONTAINER" createdb -U "$DB_USER" "$DRILL_DB" \
    || fail "Could not create throwaway DB $DRILL_DB"

# 4. Restore from the in-container file path.
#    pg_restore may exit non-zero on benign warnings — don't fail on the exit code
#    alone; the completeness + row-count asserts below are the real verdict.
if ! docker exec "$CONTAINER" pg_restore -U "$DB_USER" --no-owner --no-privileges \
        -d "$DRILL_DB" "$IN_CONTAINER_DUMP" 2>"$ERR_FILE"; then
    log "WARN: pg_restore exited non-zero; verifying anyway:"
    sed 's/^/    /' "$ERR_FILE" >&2
fi

# 5. Schema completeness: a full restore recreates every table
TABLE_COUNT="$(docker exec "$CONTAINER" psql -U "$DB_USER" -tAc \
    "SELECT count(*) FROM pg_tables WHERE schemaname='public'" "$DRILL_DB" 2>/dev/null)"
[[ "$TABLE_COUNT" =~ ^[0-9]+$ ]] || fail "Could not count restored tables (dump: $LATEST_DUMP)"
[ "$TABLE_COUNT" -ge "$MIN_TABLES" ] \
    || fail "Only $TABLE_COUNT public tables restored (expected ≥ $MIN_TABLES) — partial restore, dump: $LATEST_DUMP"
log "Public tables restored: $TABLE_COUNT"

# 6. Assert core tables are queryable, and the users table has data
for tbl in $ASSERT_TABLES; do
    COUNT="$(docker exec "$CONTAINER" psql -U "$DB_USER" -tAc "SELECT count(*) FROM $tbl" "$DRILL_DB" 2>/dev/null)"
    if ! [[ "$COUNT" =~ ^[0-9]+$ ]]; then
        fail "Table '$tbl' missing or unqueryable after restore (dump: $LATEST_DUMP)"
    fi
    log "  $tbl: $COUNT rows"
    if [ "$tbl" = "$NONEMPTY_TABLE" ] && [ "$COUNT" -eq 0 ]; then
        fail "Table '$NONEMPTY_TABLE' restored with 0 rows — suspect dump: $LATEST_DUMP"
    fi
done

log "Restore-drill OK: $LATEST_DUMP restored and verified (${TABLE_COUNT} tables, ${ASSERT_TABLES// /, })"
