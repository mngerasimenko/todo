#!/bin/bash
# Скрипт для отправки полного отчёта о состоянии сервера в Telegram
# Запуск: /root/monitoring/server-status.sh

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/monitor.conf"

send_telegram() {
    local message="$1"
    curl -s -X POST "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage" \
        -d chat_id="${TELEGRAM_CHAT_ID}" \
        -d parse_mode="HTML" \
        -d text="${message}" > /dev/null 2>&1
}

hostname=$(hostname)
timestamp=$(date '+%Y-%m-%d %H:%M:%S')
uptime_info=$(uptime -p)

# RAM
ram_total=$(free -m | awk '/^Mem:/ {print $2}')
ram_used=$(free -m | awk '/^Mem:/ {print $3}')
ram_available=$(free -m | awk '/^Mem:/ {print $7}')
ram_percent=$((ram_used * 100 / ram_total))

# Swap
swap_total=$(free -m | awk '/^Swap:/ {print $2}')
swap_used=$(free -m | awk '/^Swap:/ {print $3}')
if [ "$swap_total" -gt 0 ]; then
    swap_percent=$((swap_used * 100 / swap_total))
    swap_info="${swap_used}/${swap_total} MB (${swap_percent}%)"
else
    swap_info="не настроен"
fi

# Disk
disk_info=$(df -h / | awk 'NR==2 {printf "%s/%s (%s)", $3, $2, $5}')

# Docker containers
container_lines=""
containers="todo-app postgres-db nginx-proxy todo-web certbot"
for container in $containers; do
    status=$(docker inspect -f '{{.State.Status}}' "$container" 2>/dev/null || echo "not found")
    if [ "$status" = "running" ]; then
        icon="✅"
    else
        icon="❌"
    fi
    container_lines="${container_lines}
  ${icon} ${container}: ${status}"
done

# Docker memory usage
docker_mem=$(docker stats --no-stream --format "  {{.Name}}: {{.MemUsage}}" 2>/dev/null | head -5)

# API check (через nginx, порт 80 — todo-app не пробрасывает порт наружу)
http_code=$(curl -s -o /dev/null -w "%{http_code}" -Lk --max-time 10 http://localhost/api/status 2>/dev/null)
if [ "$http_code" = "200" ]; then
    api_status="✅ OK (200)"
else
    api_status="❌ HTTP ${http_code}"
fi

# PostgreSQL connections
pg_connections=$(docker exec postgres-db psql -U postgres -t -c "SELECT count(*) FROM pg_stat_activity;" 2>/dev/null | tr -d ' ')

message="📊 <b>Статус: ${hostname}</b>
📅 ${timestamp}
⏱ ${uptime_info}

<b>RAM:</b> ${ram_used}/${ram_total} MB (${ram_percent}%)
<b>Available:</b> ${ram_available} MB
<b>Swap:</b> ${swap_info}
<b>Disk:</b> ${disk_info}

<b>Контейнеры:</b>${container_lines}

<b>Memory:</b>
${docker_mem}

<b>API:</b> ${api_status}
<b>PG connections:</b> ${pg_connections:-N/A}/50"

send_telegram "$message"
echo "Отчёт отправлен в Telegram"
