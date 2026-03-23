#!/bin/bash
# Скрипт мониторинга сервера — отправляет алерты в Telegram
# Устанавливается через cron: */5 * * * * /root/monitoring/server-monitor.sh

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/monitor.conf"
ALERT_STATE_FILE="/tmp/server-monitor-alert-state"

send_alert() {
    local message="$1"
    local random_id=$((RANDOM * RANDOM))
    curl -s --max-time 15 -X POST "https://api.vk.com/method/messages.send" \
        -d "access_token=${VK_TOKEN}" \
        -d "peer_id=${VK_PEER_ID}" \
        -d "random_id=${random_id}" \
        --data-urlencode "message=${message}" \
        -d "v=${VK_API_VERSION}" > /dev/null 2>&1
}

# Не спамить одинаковыми алертами — отправлять повторно не чаще чем раз в 30 минут
should_alert() {
    local alert_key="$1"
    local state_file="${ALERT_STATE_FILE}_${alert_key}"
    local now=$(date +%s)

    if [ -f "$state_file" ]; then
        local last_alert=$(cat "$state_file")
        local diff=$((now - last_alert))
        if [ "$diff" -lt "${ALERT_COOLDOWN:-1800}" ]; then
            return 1  # Ещё рано повторять
        fi
    fi
    echo "$now" > "$state_file"
    return 0
}

alerts=""
hostname=$(hostname)
timestamp=$(date '+%Y-%m-%d %H:%M:%S')

# === 1. Проверка RAM ===
ram_total=$(free | awk '/^Mem:/ {print $2}')
ram_used=$(free | awk '/^Mem:/ {print $3}')
ram_percent=$((ram_used * 100 / ram_total))
ram_used_mb=$((ram_used / 1024))
ram_total_mb=$((ram_total / 1024))

if [ "$ram_percent" -ge "$RAM_WARN" ]; then
    if should_alert "ram"; then
        alerts="${alerts}
⚠️ RAM: ${ram_percent}% (${ram_used_mb}/${ram_total_mb} MB)"
    fi
fi

# === 2. Проверка Swap ===
swap_total=$(free | awk '/^Swap:/ {print $2}')
if [ "$swap_total" -gt 0 ]; then
    swap_used=$(free | awk '/^Swap:/ {print $3}')
    swap_percent=$((swap_used * 100 / swap_total))
    swap_used_mb=$((swap_used / 1024))
    swap_total_mb=$((swap_total / 1024))

    if [ "$swap_percent" -ge "$SWAP_WARN" ]; then
        if should_alert "swap"; then
            alerts="${alerts}
⚠️ Swap: ${swap_percent}% (${swap_used_mb}/${swap_total_mb} MB)"
        fi
    fi
fi

# === 3. Проверка диска ===
disk_percent=$(df / | awk 'NR==2 {gsub(/%/,""); print $5}')
disk_used=$(df -h / | awk 'NR==2 {print $3}')
disk_total=$(df -h / | awk 'NR==2 {print $2}')

if [ "$disk_percent" -ge "$DISK_WARN" ]; then
    if should_alert "disk"; then
        alerts="${alerts}
⚠️ Disk: ${disk_percent}% (${disk_used}/${disk_total})"
    fi
fi

# === 4. Проверка контейнеров ===
containers="${MONITOR_CONTAINERS:-todo-app postgres-db nginx-proxy todo-web}"
for container in $containers; do
    status=$(docker inspect -f '{{.State.Status}}' "$container" 2>/dev/null)
    if [ "$status" != "running" ]; then
        if should_alert "container_${container}"; then
            alerts="${alerts}
🔴 ${container}: ${status:-not found}"
        fi
    fi
done

# === 5. Проверка доступности API (через HTTPS, минуя редирект) ===
http_code=$(curl -s -o /dev/null -w "%{http_code}" -k --max-time 15 https://localhost/api/status 2>/dev/null)
if [ "$http_code" != "200" ]; then
    if should_alert "api"; then
        alerts="${alerts}
🔴 API: HTTP ${http_code} (ожидался 200)"
    fi
fi

# === 6. Проверка SMTP (через /api/status -> smtp_healthy) ===
smtp_healthy=$(curl -s -k --max-time 15 https://localhost/api/status 2>/dev/null | grep -o '"smtp_healthy":[a-z]*' | grep -c 'true')
if [ "$smtp_healthy" -eq 0 ] && [ "$http_code" = "200" ]; then
    if should_alert "smtp"; then
        alerts="${alerts}
🔴 SMTP: недоступен (mail.hosting.reg.ru)"
    fi
fi

# === 7. Проверка PostgreSQL ===
pg_ok=$(docker exec postgres-db pg_isready -U postgres 2>/dev/null | grep -c "accepting connections")
if [ "$pg_ok" -eq 0 ]; then
    if should_alert "postgres"; then
        alerts="${alerts}
🔴 PostgreSQL: не принимает соединения"
    fi
fi

# === 8. Проверка JVM-памяти через Actuator ===
jvm_heap_json=$(docker exec todo-app wget -qO- "http://localhost:8091/actuator/metrics/jvm.memory.used?tag=area:heap" 2>/dev/null)
jvm_heap_max_json=$(docker exec todo-app wget -qO- "http://localhost:8091/actuator/metrics/jvm.memory.max?tag=area:heap" 2>/dev/null)
jvm_nonheap_json=$(docker exec todo-app wget -qO- "http://localhost:8091/actuator/metrics/jvm.memory.used?tag=area:nonheap" 2>/dev/null)

if [ -n "$jvm_heap_json" ] && [ -n "$jvm_heap_max_json" ]; then
    heap_used=$(echo "$jvm_heap_json" | grep -o '"value":[0-9.]*' | head -1 | cut -d: -f2 | cut -d. -f1)
    heap_max=$(echo "$jvm_heap_max_json" | grep -o '"value":[0-9.]*' | head -1 | cut -d: -f2 | cut -d. -f1)
    nonheap_used=$(echo "$jvm_nonheap_json" | grep -o '"value":[0-9.]*' | head -1 | cut -d: -f2 | cut -d. -f1)

    if [ -n "$heap_used" ] && [ -n "$heap_max" ] && [ "$heap_max" -gt 0 ]; then
        heap_percent=$((heap_used * 100 / heap_max))
        heap_used_mb=$((heap_used / 1048576))
        heap_max_mb=$((heap_max / 1048576))
        nonheap_used_mb=$((${nonheap_used:-0} / 1048576))

        if [ "$heap_percent" -ge "${JVM_HEAP_WARN:-85}" ]; then
            if should_alert "jvm_heap"; then
                alerts="${alerts}
⚠️ JVM Heap: ${heap_percent}% (${heap_used_mb}/${heap_max_mb} MB)
   Non-Heap: ${nonheap_used_mb} MB"
            fi
        fi
    fi
fi

# === Отправка алерта ===
if [ -n "$alerts" ]; then
    message="🚨 Алерт: ${hostname}
📅 ${timestamp}
${alerts}"
    send_alert "$message"
fi
