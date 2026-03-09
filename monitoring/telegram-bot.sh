#!/bin/bash
# Telegram-бот для управления сервером
# Запуск: bash /root/monitoring/telegram-bot.sh
# Автозапуск: systemd сервис server-monitor-bot

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/monitor.conf"

OFFSET_FILE="/tmp/telegram-bot-offset"
POLL_INTERVAL=2

send_message() {
    local chat_id="$1"
    local text="$2"
    curl -s -X POST "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage" \
        --data-urlencode "chat_id=${chat_id}" \
        --data-urlencode "parse_mode=HTML" \
        --data-urlencode "text=${text}" > /dev/null 2>&1
}

# Получить полный статус сервера
cmd_status() {
    local chat_id="$1"
    local hostname=$(hostname)
    local timestamp=$(date '+%Y-%m-%d %H:%M:%S')
    local uptime_info=$(uptime -p)

    local ram_total=$(free -m | awk '/^Mem:/ {print $2}')
    local ram_used=$(free -m | awk '/^Mem:/ {print $3}')
    local ram_available=$(free -m | awk '/^Mem:/ {print $7}')
    local ram_percent=$((ram_used * 100 / ram_total))

    local swap_total=$(free -m | awk '/^Swap:/ {print $2}')
    local swap_used=$(free -m | awk '/^Swap:/ {print $3}')
    if [ "$swap_total" -gt 0 ]; then
        local swap_percent=$((swap_used * 100 / swap_total))
        local swap_info="${swap_used}/${swap_total} MB (${swap_percent}%)"
    else
        local swap_info="не настроен"
    fi

    local disk_info=$(df -h / | awk 'NR==2 {printf "%s/%s (%s)", $3, $2, $5}')

    local container_lines=""
    for container in todo-app postgres-db nginx-proxy todo-web certbot; do
        local status=$(docker inspect -f '{{.State.Status}}' "$container" 2>/dev/null || echo "not found")
        if [ "$status" = "running" ]; then
            container_lines="${container_lines}
  ✅ ${container}: ${status}"
        else
            container_lines="${container_lines}
  ❌ ${container}: ${status}"
        fi
    done

    local docker_mem=$(docker stats --no-stream --format "  {{.Name}}: {{.MemUsage}}" 2>/dev/null | head -5)

    local http_code=$(curl -s -o /dev/null -w "%{http_code}" -Lk --max-time 10 http://localhost/api/status 2>/dev/null)
    if [ "$http_code" = "200" ]; then
        local api_status="✅ OK (200)"
    else
        local api_status="❌ HTTP ${http_code}"
    fi

    local pg_connections=$(docker exec postgres-db psql -U postgres -t -c "SELECT count(*) FROM pg_stat_activity;" 2>/dev/null | tr -d ' ')

    send_message "$chat_id" "📊 <b>Статус: ${hostname}</b>
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
}

# Перезапуск контейнера
cmd_restart() {
    local chat_id="$1"
    local container="$2"

    local allowed="todo-app postgres-db nginx-proxy todo-web certbot"
    if ! echo "$allowed" | grep -qw "$container"; then
        send_message "$chat_id" "❌ Неизвестный контейнер: <b>${container}</b>
Доступные: todo-app, postgres-db, nginx-proxy, todo-web, certbot"
        return
    fi

    send_message "$chat_id" "🔄 Перезапуск <b>${container}</b>..."
    local result=$(docker restart "$container" 2>&1)
    local exit_code=$?

    if [ "$exit_code" -eq 0 ]; then
        send_message "$chat_id" "✅ <b>${container}</b> перезапущен"
    else
        send_message "$chat_id" "❌ Ошибка перезапуска <b>${container}</b>:
${result}"
    fi
}

# Последние логи приложения
cmd_logs() {
    local chat_id="$1"
    local lines="${2:-20}"

    # Ограничить максимум 50 строк
    if [ "$lines" -gt 50 ] 2>/dev/null; then
        lines=50
    fi

    local logs=$(docker logs --tail "$lines" todo-app 2>&1 | head -50)

    if [ -z "$logs" ]; then
        send_message "$chat_id" "📋 Логи пусты"
        return
    fi

    # Telegram ограничивает сообщение 4096 символами
    logs=$(echo "$logs" | head -c 3500)

    send_message "$chat_id" "📋 <b>Логи todo-app</b> (последние ${lines}):

<pre>${logs}</pre>"
}

# Последние ошибки
cmd_errors() {
    local chat_id="$1"

    local errors=$(docker logs --tail 200 todo-app 2>&1 | grep -i "ERROR\|Exception\|WARN" | tail -15)

    if [ -z "$errors" ]; then
        send_message "$chat_id" "✅ Ошибок не найдено (последние 200 строк)"
        return
    fi

    errors=$(echo "$errors" | head -c 3500)

    send_message "$chat_id" "⚠️ <b>Ошибки todo-app:</b>

<pre>${errors}</pre>"
}

# Текущие настройки
cmd_config() {
    local chat_id="$1"
    send_message "$chat_id" "⚙️ <b>Настройки алертов:</b>

<b>RAM:</b> ${RAM_WARN:-80}%
<b>Swap:</b> ${SWAP_WARN:-50}%
<b>Disk:</b> ${DISK_WARN:-85}%
<b>Cooldown:</b> $((${ALERT_COOLDOWN:-1800} / 60)) мин
<b>Контейнеры:</b> ${MONITOR_CONTAINERS:-todo-app postgres-db nginx-proxy todo-web}

Файл: /root/monitoring/monitor.conf"
}

# Справка
cmd_help() {
    local chat_id="$1"
    send_message "$chat_id" "🤖 <b>Команды бота:</b>

/status — полный отчёт о сервере
/restart [контейнер] — перезапустить контейнер
/logs [N] — последние N строк логов (по умолч. 20)
/errors — последние ошибки из логов
/config — настройки алертов
/help — эта справка

<b>Контейнеры:</b> todo-app, postgres-db, nginx-proxy, todo-web, certbot"
}

# Обработка входящих сообщений
process_update() {
    local chat_id="$1"
    local text="$2"

    # Проверка авторизации — только наш chat_id
    if [ "$chat_id" != "$TELEGRAM_CHAT_ID" ]; then
        return
    fi

    # Убрать @botname из команды
    text=$(echo "$text" | sed 's/@[^ ]*//')

    local cmd=$(echo "$text" | awk '{print $1}')
    local arg=$(echo "$text" | awk '{print $2}')

    case "$cmd" in
        /status)   cmd_status "$chat_id" ;;
        /restart)  cmd_restart "$chat_id" "$arg" ;;
        /logs)     cmd_logs "$chat_id" "$arg" ;;
        /errors)   cmd_errors "$chat_id" ;;
        /config)   cmd_config "$chat_id" ;;
        /help|/start) cmd_help "$chat_id" ;;
    esac
}

# Основной цикл
echo "Бот запущен. Слушаю команды..."

# Загрузить offset
offset=0
if [ -f "$OFFSET_FILE" ]; then
    offset=$(cat "$OFFSET_FILE")
fi

while true; do
    # Long polling (таймаут 30 сек)
    response=$(curl -s --max-time 35 "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/getUpdates?offset=${offset}&timeout=30" 2>/dev/null)

    if [ -z "$response" ]; then
        sleep "$POLL_INTERVAL"
        continue
    fi

    # Проверка что ответ ok
    ok=$(echo "$response" | python3 -c "import sys,json; print(json.load(sys.stdin).get('ok',''))" 2>/dev/null)
    if [ "$ok" != "True" ]; then
        sleep "$POLL_INTERVAL"
        continue
    fi

    # Обработка каждого update
    count=$(echo "$response" | python3 -c "import sys,json; print(len(json.load(sys.stdin).get('result',[])))" 2>/dev/null)

    if [ "$count" -gt 0 ] 2>/dev/null; then
        for i in $(seq 0 $((count - 1))); do
            update_id=$(echo "$response" | python3 -c "import sys,json; r=json.load(sys.stdin)['result'][$i]; print(r['update_id'])" 2>/dev/null)
            chat_id=$(echo "$response" | python3 -c "import sys,json; r=json.load(sys.stdin)['result'][$i]; print(r.get('message',{}).get('chat',{}).get('id',''))" 2>/dev/null)
            text=$(echo "$response" | python3 -c "import sys,json; r=json.load(sys.stdin)['result'][$i]; print(r.get('message',{}).get('text',''))" 2>/dev/null)

            if [ -n "$chat_id" ] && [ -n "$text" ]; then
                process_update "$chat_id" "$text"
            fi

            offset=$((update_id + 1))
            echo "$offset" > "$OFFSET_FILE"
        done
    fi
done
