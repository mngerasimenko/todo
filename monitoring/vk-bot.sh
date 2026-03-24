#!/bin/bash
# VK-бот для управления сервером
# Замена telegram-bot.sh — Telegram заблокирован с серверов в РФ

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/monitor.conf"

reload_config() {
    source "${SCRIPT_DIR}/monitor.conf"
}

send_message() {
    local peer_id="$1"
    local text="$2"
    local random_id=$((RANDOM * RANDOM))
    curl -s --max-time 15 -X POST "https://api.vk.com/method/messages.send" \
        -d "access_token=${VK_TOKEN}" \
        -d "peer_id=${peer_id}" \
        -d "random_id=${random_id}" \
        --data-urlencode "message=${text}" \
        -d "v=${VK_API_VERSION}" > /dev/null 2>&1
}

cmd_status() {
    local peer_id="$1"
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
    local all_containers="todo-app postgres-db nginx-proxy todo-web certbot clickmebattle-app clickmebattle-redis"
    for container in $all_containers; do
        local status=$(docker inspect -f '{{.State.Status}}' "$container" 2>/dev/null || echo "not found")
        if [ "$status" = "running" ]; then
            container_lines="${container_lines}
  ✅ ${container}"
        elif [ "$status" != "not found" ]; then
            container_lines="${container_lines}
  ❌ ${container}: ${status}"
        fi
    done

    local docker_mem=$(docker stats --no-stream --format "  {{.Name}}: {{.MemUsage}}" 2>/dev/null | head -7)

    local http_code=$(curl -s -o /dev/null -w "%{http_code}" -Lk --max-time 10 http://localhost/api/status 2>/dev/null)
    if [ "$http_code" = "200" ]; then
        local api_status="✅ OK (200)"
    else
        local api_status="❌ HTTP ${http_code}"
    fi

    local pg_connections=$(docker exec postgres-db psql -U postgres -t -c "SELECT count(*) FROM pg_stat_activity;" 2>/dev/null | tr -d ' ')
    local pg_max=$(docker exec postgres-db psql -U postgres -t -c "SHOW max_connections;" 2>/dev/null | tr -d ' ')

    local backup_info="нет бэкапов"
    local latest_backup=$(ls -t /root/backups/todo-*.dump 2>/dev/null | head -1)
    if [ -n "$latest_backup" ]; then
        local backup_date=$(stat -c '%y' "$latest_backup" 2>/dev/null | cut -d. -f1)
        local backup_size=$(du -h "$latest_backup" 2>/dev/null | cut -f1)
        local backup_count=$(ls /root/backups/todo-*.dump 2>/dev/null | wc -l)
        backup_info="${backup_date} (${backup_size}, ${backup_count} шт.)"
    fi

    send_message "$peer_id" "📊 Статус: ${hostname}
📅 ${timestamp}
⏱ ${uptime_info}

RAM: ${ram_used}/${ram_total} MB (${ram_percent}%)
Available: ${ram_available} MB
Swap: ${swap_info}
Disk: ${disk_info}

Контейнеры:${container_lines}

Memory:
${docker_mem}

API: ${api_status}
PG connections: ${pg_connections:-N/A}/${pg_max:-?}

💾 Последний backup: ${backup_info}"
}

cmd_restart() {
    local peer_id="$1"
    local container="$2"

    local allowed="todo-app postgres-db nginx-proxy todo-web certbot clickmebattle-app clickmebattle-redis"
    if ! echo "$allowed" | grep -qw "$container"; then
        send_message "$peer_id" "❌ Неизвестный контейнер: ${container}
Доступные: todo-app, postgres-db, nginx-proxy, todo-web, certbot, clickmebattle-app, clickmebattle-redis"
        return
    fi

    send_message "$peer_id" "🔄 Перезапуск ${container}..."
    local result=$(docker restart "$container" 2>&1)
    local exit_code=$?

    if [ "$exit_code" -eq 0 ]; then
        send_message "$peer_id" "✅ ${container} перезапущен"
    else
        send_message "$peer_id" "❌ Ошибка перезапуска ${container}: ${result}"
    fi
}

cmd_logs() {
    local peer_id="$1"
    local lines="${2:-20}"
    if [ "$lines" -gt 50 ] 2>/dev/null; then lines=50; fi

    local logs=$(docker logs --tail "$lines" todo-app 2>&1 | head -50)
    if [ -z "$logs" ]; then
        send_message "$peer_id" "📋 Логи пусты"
        return
    fi
    logs=$(echo "$logs" | head -c 3500)
    send_message "$peer_id" "📋 Логи todo-app (последние ${lines}):

${logs}"
}

cmd_errors() {
    local peer_id="$1"
    local errors=$(docker logs --tail 200 todo-app 2>&1 | grep -i "ERROR\|Exception\|WARN" | tail -15)
    if [ -z "$errors" ]; then
        send_message "$peer_id" "✅ Ошибок не найдено (последние 200 строк)"
        return
    fi
    errors=$(echo "$errors" | head -c 3500)
    send_message "$peer_id" "⚠️ Ошибки todo-app:

${errors}"
}

cmd_config() {
    local peer_id="$1"
    send_message "$peer_id" "⚙️ Настройки алертов:

RAM: ${RAM_WARN:-80}%
Swap: ${SWAP_WARN:-50}%
Disk: ${DISK_WARN:-85}%
Cooldown: $((${ALERT_COOLDOWN:-1800} / 60)) мин
Контейнеры: ${MONITOR_CONTAINERS:-todo-app postgres-db nginx-proxy todo-web}

Файл: /root/monitoring/monitor.conf"
}

cmd_jvm() {
    local peer_id="$1"
    local base="http://localhost:8091/actuator/metrics"

    get_metric() {
        docker exec todo-app wget -qO- "$1" 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin)['measurements'][0]['value'])" 2>/dev/null
    }

    local heap_used_raw=$(get_metric "${base}/jvm.memory.used?tag=area:heap")
    local heap_max_raw=$(get_metric "${base}/jvm.memory.max?tag=area:heap")
    local nonheap_used_raw=$(get_metric "${base}/jvm.memory.used?tag=area:nonheap")
    local threads_live=$(get_metric "${base}/jvm.threads.live" | cut -d. -f1)
    local threads_peak=$(get_metric "${base}/jvm.threads.peak" | cut -d. -f1)

    if [ -z "$heap_used_raw" ]; then
        send_message "$peer_id" "❌ Actuator недоступен (порт 8091). Нужен деплой с actuator."
        return
    fi

    local heap_used_mb=$(echo "$heap_used_raw" | awk '{printf "%.0f", $1/1048576}')
    local heap_max_mb=$(echo "$heap_max_raw" | awk '{printf "%.0f", $1/1048576}')
    local heap_percent=$(echo "$heap_used_raw $heap_max_raw" | awk '{printf "%.0f", $1*100/$2}')
    local nonheap_mb=$(echo "${nonheap_used_raw:-0}" | awk '{printf "%.0f", $1/1048576}')

    local hikari_active=$(get_metric "${base}/hikaricp.connections.active" | cut -d. -f1)
    local hikari_idle=$(get_metric "${base}/hikaricp.connections.idle" | cut -d. -f1)
    local hikari_total=$(get_metric "${base}/hikaricp.connections" | cut -d. -f1)

    local gc_count=$(docker exec todo-app wget -qO- "${base}/jvm.gc.pause" 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(sum(m['value'] for m in d.get('measurements',[]) if m['statistic']=='COUNT'))" 2>/dev/null | cut -d. -f1)

    send_message "$peer_id" "☕ JVM-метрики

Heap: ${heap_percent}% (${heap_used_mb}/${heap_max_mb} MB)
Non-Heap: ${nonheap_mb} MB (metaspace + code cache)

Потоки: ${threads_live:-?} live / ${threads_peak:-?} peak

HikariCP: ${hikari_active:-?} active / ${hikari_idle:-?} idle / ${hikari_total:-?} total

GC: ${gc_count:-?} collections"
}

cmd_stats() {
    local peer_id="$1"
    local period="${2:-2}"

    local url="http://localhost:8091/actuator/usagestats"
    if [ "$period" != "2" ]; then
        url="${url}/${period}"
    fi
    local stats_json=$(docker exec todo-app wget -qO- "$url" 2>/dev/null)

    if [ -z "$stats_json" ]; then
        send_message "$peer_id" "❌ Actuator usagestats недоступен"
        return
    fi

    local msg=$(echo "$stats_json" | python3 -c "
import sys, json
d = json.load(sys.stdin)
u = d.get('users', {})
l = d.get('lists', {})
t = d.get('tasks', {})
a = d.get('activity', {})

period = d.get('period_hours', '?')
names = ', '.join(u.get('new_user_names', [])) or 'нет'

print(f'''📈 Статистика (за {period}ч)

👥 Пользователи: {u.get('total', '?')} (новых: {u.get('new_in_period', '?')} — {names})
   Email подтверждён: {u.get('email_verified', '?')} ({u.get('email_verification_rate', 0):.0f}%)

📋 Списки: {l.get('total', '?')} (новых: {l.get('new_in_period', '?')})
   Среднее на пользователя: {l.get('avg_lists_per_user', 0):.1f}

✅ Задачи: {t.get('total', '?')} (новых: {t.get('new_in_period', '?')})
   Выполнено: {t.get('completed_total', '?')} ({t.get('completion_rate', 0):.0f}%), за период: {t.get('completed_in_period', '?')}
   В ожидании: {t.get('pending_total', '?')}
   Среднее на пользователя: {t.get('avg_tasks_per_user', 0):.1f}
   Среднее на список: {t.get('avg_tasks_per_list', 0):.1f}

🔥 Активность: {a.get('active_users_last_24h', '?')} за 24ч, {a.get('active_users_last_7d', '?')} за 7д
🔗 Приглашения: {a.get('active_invite_tokens', '?')} активных''')
" 2>/dev/null)

    if [ -z "$msg" ]; then
        send_message "$peer_id" "❌ Ошибка парсинга статистики"
        return
    fi

    send_message "$peer_id" "$msg"
}

cmd_help() {
    local peer_id="$1"
    send_message "$peer_id" "🤖 Команды бота:

/status — полный отчёт о сервере
/jvm — JVM-метрики (heap, threads, HikariCP, GC)
/stats — статистика использования приложения
/restart [контейнер] — перезапустить контейнер
/logs [N] — последние N строк логов (по умолч. 20)
/errors — последние ошибки из логов
/config — настройки алертов
/help — эта справка

Контейнеры: todo-app, postgres-db, nginx-proxy, todo-web, certbot, clickmebattle-app, clickmebattle-redis"
}

process_message() {
    local peer_id="$1"
    local text="$2"

    if [ "$peer_id" != "$VK_PEER_ID" ]; then
        return
    fi

    local cmd=$(echo "$text" | awk '{print $1}')
    local arg=$(echo "$text" | awk '{print $2}')

    reload_config

    case "$cmd" in
        /status)   cmd_status "$peer_id" ;;
        /jvm)      cmd_jvm "$peer_id" ;;
        /restart)  cmd_restart "$peer_id" "$arg" ;;
        /logs)     cmd_logs "$peer_id" "$arg" ;;
        /errors)   cmd_errors "$peer_id" ;;
        /stats)    cmd_stats "$peer_id" "$arg" ;;
        /config)   cmd_config "$peer_id" ;;
        /help|/start|привет|Привет) cmd_help "$peer_id" ;;
    esac
}

# Main loop — VK Bots Long Poll
echo "VK-бот запущен. Слушаю команды..."

while true; do
    lp_response=$(curl -s --max-time 15 "https://api.vk.com/method/groups.getLongPollServer?access_token=${VK_TOKEN}&group_id=${VK_GROUP_ID}&v=${VK_API_VERSION}" 2>/dev/null)

    lp_server=$(echo "$lp_response" | python3 -c "import sys,json; print(json.load(sys.stdin).get('response',{}).get('server',''))" 2>/dev/null)
    lp_key=$(echo "$lp_response" | python3 -c "import sys,json; print(json.load(sys.stdin).get('response',{}).get('key',''))" 2>/dev/null)
    lp_ts=$(echo "$lp_response" | python3 -c "import sys,json; print(json.load(sys.stdin).get('response',{}).get('ts',''))" 2>/dev/null)

    if [ -z "$lp_server" ] || [ -z "$lp_key" ]; then
        echo "Ошибка получения Long Poll сервера, повтор через 10 сек..."
        sleep 10
        continue
    fi

    echo "Long Poll подключён: ts=${lp_ts}"

    while true; do
        response=$(curl -s --max-time 35 "${lp_server}?act=a_check&key=${lp_key}&ts=${lp_ts}&wait=25" 2>/dev/null)

        if [ -z "$response" ]; then
            sleep 2
            continue
        fi

        failed=$(echo "$response" | python3 -c "import sys,json; print(json.load(sys.stdin).get('failed',0))" 2>/dev/null)
        if [ "$failed" -gt 1 ] 2>/dev/null; then
            echo "Long Poll требует переподключения (failed=${failed})"
            break
        fi

        new_ts=$(echo "$response" | python3 -c "import sys,json; print(json.load(sys.stdin).get('ts',''))" 2>/dev/null)
        if [ -n "$new_ts" ]; then
            lp_ts="$new_ts"
        fi

        count=$(echo "$response" | python3 -c "import sys,json; print(len(json.load(sys.stdin).get('updates', [])))" 2>/dev/null)

        if [ "$count" -gt 0 ] 2>/dev/null; then
            for i in $(seq 0 $((count - 1))); do
                update_type=$(echo "$response" | python3 -c "import sys,json; u=json.load(sys.stdin)['updates'][$i]; print(u.get('type',''))" 2>/dev/null)

                if [ "$update_type" = "message_new" ]; then
                    peer_id=$(echo "$response" | python3 -c "import sys,json; u=json.load(sys.stdin)['updates'][$i]; print(u.get('object',{}).get('message',{}).get('peer_id',''))" 2>/dev/null)
                    text=$(echo "$response" | python3 -c "import sys,json; u=json.load(sys.stdin)['updates'][$i]; print(u.get('object',{}).get('message',{}).get('text',''))" 2>/dev/null)

                    if [ -n "$peer_id" ] && [ -n "$text" ]; then
                        process_message "$peer_id" "$text"
                    fi
                fi
            done
        fi
    done
done
