#!/bin/bash
# Скрипт мониторинга сервера — отправляет алерты в VK (Telegram с российских
# хостов заблокирован и не используется).
#
# Строка cron на проде (с редиректом обоих потоков в файл — MTA на машине нет,
# и без редиректа причина несостоявшейся отправки уходила бы в никуда):
#   */5 * * * * /home/deploy/todo/monitoring/server-monitor.sh >> /var/log/server-monitor.log 2>&1

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Конфига нет в git: на пересобранном хосте его кладут руками, и забыть это
# легко. Без него пусты и учётка VK, и пороги: каждое сравнение падает в лог
# «integer expression expected», ни один алерт не срабатывает, а прогон выходит
# с нулём — то есть мониторинг выключен и молчит об этом ровно как исправный.
if [ ! -r "${SCRIPT_DIR}/monitor.conf" ]; then
    echo "server-monitor: нет ${SCRIPT_DIR}/monitor.conf — ни порогов, ни учётки VK" >&2
    exit 1
fi
source "${SCRIPT_DIR}/monitor.conf"
# Отправка — общая с stats-report.sh и vk-bot.sh (токен мимо argv, ответ VK
# проверяется). Без неё алерты уходить не могут: выходим громко, а не молча —
# тихий отказ мониторинга и есть та беда, от которой он поставлен.
if [ ! -r "${SCRIPT_DIR}/vk-send.sh" ]; then
    echo "server-monitor: нет ${SCRIPT_DIR}/vk-send.sh — отправлять алерты нечем" >&2
    exit 1
fi
# shellcheck source=monitoring/vk-send.sh
source "${SCRIPT_DIR}/vk-send.sh"
# Читаемость файла — ещё не пригодность: пустой или недописанный файл
# сорсится успешно, а отправка падает с «command not found» на первом же
# алерте. Требуем саму функцию.
if ! declare -F vk_send_message >/dev/null; then
    echo "server-monitor: ${SCRIPT_DIR}/vk-send.sh не дал vk_send_message — отправлять нечем" >&2
    exit 1
fi
# Состояние кулдауна переопределяется конфигом (им пользуются тесты). По
# умолчанию — каталог, куда пишет только root: в общем /tmp любой локальный
# пользователь с shell'ом мог заранее положить отметку с датой из будущего и
# навсегда заглушить алерт, а на симлинке — заставить root обрезать чужой файл.
# После этого изменения отметки ещё и несут доставку (недоставленный алерт их
# снимает), так что чужая запись сюда тем более не годится.
ALERT_STATE_FILE="${ALERT_STATE_FILE:-/var/lib/server-monitor/alert-state}"
ALERT_STATE_DIR="$(dirname "$ALERT_STATE_FILE")"
if [ ! -d "$ALERT_STATE_DIR" ]; then
    # Не создался — говорим и работаем дальше без кулдауна: повторяющийся алерт
    # шумен, но виден, а тихо пропущенный — нет.
    mkdir -p "$ALERT_STATE_DIR" 2>/dev/null \
        && chmod 700 "$ALERT_STATE_DIR" 2>/dev/null \
        || echo "server-monitor: нет каталога состояния ${ALERT_STATE_DIR} — кулдаун алертов не работает" >&2
fi

# Ключи алертов, попавшие в это сообщение: если отправка не дойдёт, их отметки
# кулдауна снимаются — иначе недоставленный алерт молча пропадал бы на полчаса.
ALERTED_KEYS=()

send_alert() {
    local message="$1" k
    if vk_send_message "$message"; then
        return 0
    fi
    for k in "${ALERTED_KEYS[@]}"; do
        rm -f "${ALERT_STATE_FILE}_${k}"
    done
    return 1
}

# Не спамить одинаковыми алертами.
# Аргументы:
#   $1 — alert_key (обязательный)
#   $2 — cooldown в секундах (опционально, по умолчанию ALERT_COOLDOWN или 1800)
should_alert() {
    local alert_key="$1"
    local cooldown="${2:-${ALERT_COOLDOWN:-1800}}"
    local state_file="${ALERT_STATE_FILE}_${alert_key}"
    local now=$(date +%s)

    if [ -f "$state_file" ]; then
        local last_alert=$(cat "$state_file")
        local diff=$((now - last_alert))
        if [ "$diff" -lt "$cooldown" ]; then
            return 1  # Ещё рано повторять
        fi
    fi
    echo "$now" > "$state_file"
    ALERTED_KEYS+=("$alert_key")
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

# === 5. Проверка доступности API и сервисов (один запрос к /api/status) ===
api_response=$(curl -s -k --max-time 15 -w "\n%{http_code}" https://localhost/api/status 2>/dev/null)
http_code=$(echo "$api_response" | tail -1)
api_json=$(echo "$api_response" | sed '$d')

if [ "$http_code" != "200" ]; then
    sleep 10
    api_response=$(curl -s -k --max-time 15 -w "\n%{http_code}" https://localhost/api/status 2>/dev/null)
    http_code=$(echo "$api_response" | tail -1)
    api_json=$(echo "$api_response" | sed '$d')
    if [ "$http_code" != "200" ]; then
        if should_alert "api"; then
            alerts="${alerts}
🔴 API: HTTP ${http_code} (ожидался 200)"
        fi
    fi
fi

# === 6. Проверка SMTP и Firebase (из того же /api/status ответа) ===
if [ "$http_code" = "200" ]; then
    smtp_healthy=$(echo "$api_json" | grep -o '"smtp_healthy":[a-z]*' | grep -c 'true')
    if [ "$smtp_healthy" -eq 0 ]; then
        if should_alert "smtp"; then
            alerts="${alerts}
🔴 SMTP: недоступен"
        fi
    fi

    firebase_healthy=$(echo "$api_json" | grep -o '"firebase_healthy":[a-z]*' | grep -c 'true')
    if [ "$firebase_healthy" -eq 0 ]; then
        if should_alert "firebase"; then
            alerts="${alerts}
🔴 Firebase: push-уведомления недоступны"
        fi
    fi

    redis_healthy=$(echo "$api_json" | grep -o '"redis_healthy":[a-z]*' | grep -c 'true')
    if [ "$redis_healthy" -eq 0 ]; then
        if should_alert "redis" 86400; then
            alerts="${alerts}
🔴 Redis: недоступен (cache fallback на Postgres работает, но медленнее; повтор алерта раз в сутки)"
        fi
    fi
fi

# === 7. Проверка PostgreSQL ===
pg_ok=$(docker exec postgres-db pg_isready -U postgres 2>/dev/null | grep -c "accepting connections")
if [ "$pg_ok" -eq 0 ]; then
    sleep 10
    pg_ok=$(docker exec postgres-db pg_isready -U postgres 2>/dev/null | grep -c "accepting connections")
    if [ "$pg_ok" -eq 0 ]; then
        if should_alert "postgres"; then
            alerts="${alerts}
🔴 PostgreSQL: не принимает соединения"
        fi
    fi
fi

# === 8. Проверка JVM-памяти через Actuator ===
jvm_heap_json=$(docker exec todo-app wget -qO- "http://localhost:8091/actuator/metrics/jvm.memory.used?tag=area:heap" 2>/dev/null)
jvm_heap_max_json=$(docker exec todo-app wget -qO- "http://localhost:8091/actuator/metrics/jvm.memory.max?tag=area:heap" 2>/dev/null)
jvm_nonheap_json=$(docker exec todo-app wget -qO- "http://localhost:8091/actuator/metrics/jvm.memory.used?tag=area:nonheap" 2>/dev/null)

if [ -n "$jvm_heap_json" ] && [ -n "$jvm_heap_max_json" ]; then
    heap_used=$(echo "$jvm_heap_json" | python3 -c "import sys,json; print(int(json.load(sys.stdin)['measurements'][0]['value']))" 2>/dev/null)
    heap_max=$(echo "$jvm_heap_max_json" | python3 -c "import sys,json; print(int(json.load(sys.stdin)['measurements'][0]['value']))" 2>/dev/null)
    nonheap_used=$(echo "$jvm_nonheap_json" | python3 -c "import sys,json; print(int(json.load(sys.stdin)['measurements'][0]['value']))" 2>/dev/null)

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
