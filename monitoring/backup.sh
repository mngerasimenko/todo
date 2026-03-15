#!/bin/bash
# PostgreSQL daily backup script
# Crontab: 0 3 * * * /root/monitoring/backup.sh

BACKUP_DIR="/root/backups"
CONTAINER="postgres-db"
DB_NAME="todo"
DB_USER="postgres"
RETENTION_DAYS=7

mkdir -p "$BACKUP_DIR"

FILENAME="$BACKUP_DIR/$DB_NAME-$(date +%Y%m%d).dump"

# Create backup
if docker exec "$CONTAINER" pg_dump -U "$DB_USER" -Fc "$DB_NAME" > "$FILENAME" 2>/dev/null; then
    SIZE=$(du -h "$FILENAME" | cut -f1)
    echo "$(date '+%Y-%m-%d %H:%M:%S') Backup OK: $FILENAME ($SIZE)"
else
    echo "$(date '+%Y-%m-%d %H:%M:%S') ERROR: Backup failed!" >&2
    rm -f "$FILENAME"
    exit 1
fi

# Delete backups older than retention period
DELETED=$(find "$BACKUP_DIR" -name "*.dump" -mtime +$RETENTION_DAYS -delete -print | wc -l)
if [ "$DELETED" -gt 0 ]; then
    echo "$(date '+%Y-%m-%d %H:%M:%S') Deleted $DELETED old backup(s)"
fi
