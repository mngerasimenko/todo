#!/bin/bash
# Установка мониторинга на сервер
# Запуск: bash setup-monitoring.sh

set -e

MONITOR_DIR="/root/monitoring"

echo "=== Установка мониторинга ==="

# Создание директории
mkdir -p "$MONITOR_DIR"

# Копирование скриптов (предполагается что уже скопированы через SCP)
chmod +x "$MONITOR_DIR/server-monitor.sh"
chmod +x "$MONITOR_DIR/server-status.sh"

# Установка cron (каждые 5 минут)
CRON_JOB="*/5 * * * * $MONITOR_DIR/server-monitor.sh"
(crontab -l 2>/dev/null | grep -v "server-monitor.sh"; echo "$CRON_JOB") | crontab -

echo "✅ Cron установлен: каждые 5 минут"
crontab -l | grep server-monitor

# Тестовая отправка
echo "=== Отправка тестового отчёта ==="
bash "$MONITOR_DIR/server-status.sh"

echo ""
echo "=== Готово ==="
echo "Мониторинг: каждые 5 минут (алерты при RAM>80%, Swap>50%, Disk>85%, контейнер упал)"
echo "Ручной отчёт: $MONITOR_DIR/server-status.sh"
