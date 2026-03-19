#!/bin/bash
# Offsite backup: copy PostgreSQL dump from production server
# Runs on BACKUP server (185.244.172.45), pulls dump from PRODUCTION (82.114.226.107)
# Cron: 0 4 * * * /root/monitoring/offsite-backup.sh >> /root/backups/offsite-backup.log 2>&1

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PRODUCTION_SERVER="82.114.226.107"
SSH_KEY="/root/.ssh/id_ed25519_backup"
BACKUP_DIR="/root/backups/offsite"
RETENTION_DAYS=14

mkdir -p "$BACKUP_DIR"
FILENAME="$BACKUP_DIR/todo-$(date +%Y%m%d).dump"

send_telegram_alert() {
    if [ -f "${SCRIPT_DIR}/monitor.conf" ]; then
        source "${SCRIPT_DIR}/monitor.conf"
        curl -s -X POST "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage" \
            -d chat_id="${TELEGRAM_CHAT_ID}" \
            -d parse_mode="HTML" \
            -d text="$1" > /dev/null 2>&1
    fi
}

# Create dump on production and copy via SSH
ssh -o ConnectTimeout=30 -o StrictHostKeyChecking=no -i "$SSH_KEY" root@${PRODUCTION_SERVER} \
  "docker exec postgres-db pg_dump -U postgres -Fc todo" > "$FILENAME" 2>/dev/null

if [ -s "$FILENAME" ]; then
    SIZE=$(du -h "$FILENAME" | cut -f1)
    echo "$(date '+%Y-%m-%d %H:%M:%S') Offsite backup OK: $FILENAME ($SIZE)"
else
    echo "$(date '+%Y-%m-%d %H:%M:%S') ERROR: Offsite backup failed!" >&2
    rm -f "$FILENAME"
    send_telegram_alert "🔴 <b>Offsite backup failed!</b>
📅 $(date '+%Y-%m-%d %H:%M:%S')
Host: $(hostname)
Production: ${PRODUCTION_SERVER}"
    exit 1
fi

# Delete old backups
DELETED=$(find "$BACKUP_DIR" -name "*.dump" -mtime +$RETENTION_DAYS -delete -print | wc -l)
if [ "$DELETED" -gt 0 ]; then
    echo "$(date '+%Y-%m-%d %H:%M:%S') Deleted $DELETED old backup(s)"
fi
