#!/bin/bash
# VK-бот для управления сервером
# Замена telegram-bot.sh — Telegram заблокирован с серверов в РФ

# BASH_SOURCE, а не $0: тесты подгружают файл через source, и с $0 путь указывал
# бы на каталог вызывающего, а конфиг искался бы не там.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Без конфига бот не мёртв, а хуже: `source` несуществующего файла не прерывает
# скрипт, дальше пусты и токен, и группа, и опрос Long Poll вечно возвращается с
# ошибкой — процесс не выходит, Restart=always не срабатывает, StartLimitBurst
# не считает ничего, и юнит остаётся active (running).
if [ ! -r "${SCRIPT_DIR}/monitor.conf" ]; then
    echo "vk-bot: нет ${SCRIPT_DIR}/monitor.conf — ни учётки VK, ни адресата" >&2
    exit 1
fi
source "${SCRIPT_DIR}/monitor.conf"
# Отправка — общая с server-monitor.sh и stats-report.sh: токен мимо argv, ответ
# VK проверяется. Без неё бот отвечать не может, и падение видно в journal.
if [ ! -r "${SCRIPT_DIR}/vk-send.sh" ]; then
    echo "vk-bot: нет ${SCRIPT_DIR}/vk-send.sh — отвечать нечем" >&2
    exit 1
fi
# shellcheck source=monitoring/vk-send.sh
source "${SCRIPT_DIR}/vk-send.sh"
# Читаемость файла — ещё не пригодность: пустой или недописанный файл
# сорсится успешно, а отправка падает с «command not found» на первом же
# алерте. Требуем саму функцию.
if ! declare -F vk_send_message >/dev/null; then
    echo "vk-bot: ${SCRIPT_DIR}/vk-send.sh не дал vk_send_message — отправлять нечем" >&2
    exit 1
fi

# Файл на месте — это ещё не конфиг: пустой или недописанный сорсится успешно,
# и дальше пусты токен, группа и адресат. Опрос Long Poll тогда вечно
# возвращается с ошибкой, а процесс при этом не выходит — поэтому проверяем не
# читаемость файла, а сами значения.
if [ -z "${VK_TOKEN:-}" ] || [ -z "${VK_PEER_ID:-}" ] || [ -z "${VK_GROUP_ID:-}" ]; then
    echo "vk-bot: в ${SCRIPT_DIR}/monitor.conf нет учётки VK, группы или адресата" >&2
    exit 1
fi

reload_config() {
    source "${SCRIPT_DIR}/monitor.conf"
}

send_message() {
    local peer_id="$1"
    local text="$2"
    vk_send_message "$text" "$peer_id"
}

# Long Poll сервер группы: кладёт адрес, сессионный ключ и метку в LP_SERVER,
# LP_KEY и LP_TS. Пусто в LP_SERVER или LP_KEY — выдачи не было.
fetch_longpoll_server() {
    local resp rc
    resp="$(vk_api_post groups.getLongPollServer "group_id=${VK_GROUP_ID}")"
    rc=$?
    LP_SERVER=""; LP_KEY=""; LP_TS=""
    # Сеть легла и VK отверг запрос — разные причины: первая проходит сама,
    # вторая требует человека. Одинаковая строка в журнале про каждые 10 секунд
    # превращает и то, и другое в шум.
    if [ "$rc" -ne 0 ]; then
        vk_log "curl упал (rc=${rc}) — Long Poll сервер не получен"
        return 1
    fi
    if ! vk_response_ok "$resp"; then
        vk_log "VK отверг выдачу Long Poll сервера: $(printf '%s' "$resp" | head -c 200)"
        return 1
    fi
    LP_SERVER=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin).get('response',{}).get('server',''))" 2>/dev/null)
    LP_KEY=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin).get('response',{}).get('key',''))" 2>/dev/null)
    LP_TS=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin).get('response',{}).get('ts',''))" 2>/dev/null)
    [ -n "$LP_SERVER" ] && [ -n "$LP_KEY" ]
}

# Один опрос Long Poll. Сессионный ключ уходит в URL, а URL — конфигом curl:
# в argv он виден в `ps` всякому локальному пользователю, как и токен.
poll_longpoll() {
    local server="$1" key="$2" ts="$3"
    vk_get_url "${server}?act=a_check&key=${key}&ts=${ts}&wait=25" 35
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

    local docker_mem=$(docker stats --no-stream --format "  {{.Name}}: {{.MemUsage}}" 2>/dev/null | sed 's| / [^ ]*||g' | head -7)

    local api_response=$(curl -s -Lk --max-time 10 -w "\n%{http_code}" http://localhost/api/status 2>/dev/null)
    local http_code=$(echo "$api_response" | tail -1)
    local api_json=$(echo "$api_response" | sed '$d')
    if [ "$http_code" = "200" ]; then
        local api_status="✅ OK (200)"
        local smtp_ok=$(echo "$api_json" | python3 -c "import sys,json; d=json.load(sys.stdin); print('✅' if d.get('smtp_healthy') else '❌')" 2>/dev/null || echo "?")
        local firebase_ok=$(echo "$api_json" | python3 -c "import sys,json; d=json.load(sys.stdin); print('✅' if d.get('firebase_healthy') else '❌')" 2>/dev/null || echo "?")
    else
        local api_status="❌ HTTP ${http_code}"
        local smtp_ok="?"
        local firebase_ok="?"
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
SMTP: ${smtp_ok:-?}  Firebase: ${firebase_ok:-?}
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
    # Объявление отдельно от присваивания: у `local result=$(…)` код возврата —
    # это код самой команды `local`, то есть всегда 0, и ветка ошибки ниже была
    # недостижима. Контейнер не поднялся, а владелец видел «✅ перезапущен».
    local result exit_code
    result=$(docker restart "$container" 2>&1)
    exit_code=$?

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
    # Граница символа, а не байта: битый UTF-8 VK отвергает целиком, и ответ
    # на /logs пропал бы вместо того, чтобы прийти обрезанным.
    logs=$(vk_utf8_cut "$logs" 3500)
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
    errors=$(vk_utf8_cut "$errors" 3500)
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

Файл: ${SCRIPT_DIR}/monitor.conf"
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
   Совместных: {l.get('shared_lists', '?')}
   Среднее на пользователя: {l.get('avg_lists_per_user', 0):.1f}
   Среднее участников: {l.get('avg_members_per_list', 0):.1f}

✅ Задачи: {t.get('total', '?')} (новых: {t.get('new_in_period', '?')})
   Выполнено: {t.get('completed_total', '?')} ({t.get('completion_rate', 0):.0f}%), за период: {t.get('completed_in_period', '?')}
   В ожидании: {t.get('pending_total', '?')}
   Приватных: {t.get('private_tasks', '?')}
   Среднее на пользователя: {t.get('avg_tasks_per_user', 0):.1f}
   Среднее на список: {t.get('avg_tasks_per_list', 0):.1f}

🔥 Активность: {a.get('active_users_last_24h', '?')} за 24ч, {a.get('active_users_last_3d', '?')} за 3д, {a.get('active_users_last_7d', '?')} за 7д, {a.get('active_users_last_30d', '?')} за 30д
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

# Тело отделено от определений: набор тестов подгружает файл через `source` и
# проверяет отправку, не поднимая вечный цикл опроса. Признак — сам факт
# сорсинга, а не переменная окружения: переменную можно унаследовать (профиль
# root, Environment= в юните), и тогда бот под Restart=always выходил бы с нулём
# каждые 10 секунд, выглядя в systemctl живым.
if [ "${BASH_SOURCE[0]}" != "$0" ]; then
    return 0
fi

# Main loop — VK Bots Long Poll
echo "VK-бот запущен. Слушаю команды..."

# Отказ, который сам не пройдёт — отозванный токен, забаненное сообщество,
# заблокированный адрес Long Poll, — давал вечный цикл: процесс жив, значит
# Restart=always не срабатывает, значит и StartLimitBurst в юните не считает
# ничего, и `systemctl status` зелёный. Выход с ненулевым кодом — единственный
# способ довести отказ до failed, то есть до того места, где его видно.
#
# Меряем ВРЕМЯ без единого удачного опроса, а не число попыток: цена попытки —
# таймаут curl (15 с у вызова метода, 35 с у опроса), поэтому при заблокированном
# адресе пять попыток растягиваются на минуты, один процесс живёт дольше окна
# StartLimitIntervalSec, и пять стартов в него не помещаются никогда. По времени
# задержка выхода предсказуема, и окно юнита можно посчитать.
#
# Ручки читаются из конфига хоста и потому проверяются: нечисловое значение
# превращало бы сравнение в «integer expression expected», то есть молча
# выключало бы сам потолок. Пустое значение безопасно — его перекроет умолчание.
LP_MAX_FAILURES="${VK_LP_MAX_FAILURES:-5}"
LP_RETRY_SLEEP="${VK_LP_RETRY_SLEEP:-10}"
LP_POLL_SLEEP="${VK_LP_POLL_SLEEP:-2}"
LP_DEAD_AFTER="${VK_LP_DEAD_AFTER:-120}"
case "$LP_MAX_FAILURES" in ''|*[!0-9]*|0) LP_MAX_FAILURES=5 ;; esac
case "$LP_RETRY_SLEEP"  in ''|*[!0-9]*) LP_RETRY_SLEEP=10 ;; esac
case "$LP_POLL_SLEEP"   in ''|*[!0-9]*) LP_POLL_SLEEP=2 ;; esac
case "$LP_DEAD_AFTER"   in ''|*[!0-9]*) LP_DEAD_AFTER=120 ;; esac

lp_last_ok="$(date +%s)"

# Вызывается на каждой неудаче: и при неподнявшемся подключении, и при
# неудавшемся опросе. Пока хоть один опрос проходит, счётчик времени сбрасывается
# и бот работает сколько угодно долго.
lp_give_up_if_dead() {
    local now
    now="$(date +%s)"
    if [ $(( now - lp_last_ok )) -gt "$LP_DEAD_AFTER" ]; then
        vk_log "Long Poll не отвечает дольше ${LP_DEAD_AFTER} с — выхожу, чтобы юнит перешёл в failed"
        exit 1
    fi
}

while true; do
    if ! fetch_longpoll_server; then
        lp_give_up_if_dead
        echo "Ошибка получения Long Poll сервера, повтор через ${LP_RETRY_SLEEP} с..."
        sleep "$LP_RETRY_SLEEP"
        continue
    fi
    lp_server="$LP_SERVER"
    lp_key="$LP_KEY"
    lp_ts="$LP_TS"

    echo "Long Poll подключён: ts=${lp_ts}"

    # Счётчик неудач подряд решает только одно — когда перевыпустить сессионный
    # ключ. Жив ли бот вообще, решает время с последнего удачного опроса.
    lp_fail=0

    while true; do
        response=$(poll_longpoll "$lp_server" "$lp_key" "$lp_ts")
        poll_rc=$?

        # Пустой ответ и упавший curl — не «обновлений нет»: при лёгшей сети или
        # заблокированном адресе прежний код крутил этот цикл вечно, ни разу не
        # переобновив сессионный ключ и не сказав ни слова в journal.
        if [ "$poll_rc" -ne 0 ] || [ -z "$response" ]; then
            lp_fail=$((lp_fail + 1))
            [ "$lp_fail" = 1 ] && vk_log "Long Poll: опрос не удался (rc=${poll_rc}, ответ пуст) — повторяю"
            lp_give_up_if_dead
            if [ "$lp_fail" -ge "$LP_MAX_FAILURES" ]; then
                vk_log "Long Poll: ${lp_fail} неудачных опросов подряд — переподключаюсь"
                break
            fi
            sleep "$LP_POLL_SLEEP"
            continue
        fi

        failed=$(echo "$response" | python3 -c "import sys,json; print(json.load(sys.stdin).get('failed',0))" 2>/dev/null)
        if [ "$failed" -gt 1 ] 2>/dev/null; then
            echo "Long Poll требует переподключения (failed=${failed})"
            break
        fi

        new_ts=$(echo "$response" | python3 -c "import sys,json; print(json.load(sys.stdin).get('ts',''))" 2>/dev/null)
        count=$(echo "$response" | python3 -c "import sys,json; print(len(json.load(sys.stdin).get('updates', [])))" 2>/dev/null)

        # Ответ пришёл, но это не ответ Long Poll: страница 502 от провайдера,
        # капча, заглушка хостера — или разбираемый JSON с ошибкой VK. Признак
        # один: у настоящего ответа ВСЕГДА есть ts. Судить по `updates` нельзя —
        # для любого разобранного JSON их длина равна нулю, то есть непуста, и
        # такой ответ проваливался сквозь все проверки, возвращая управление на
        # опрос немедленно: бот молотил VK без пауз и без строки в journal,
        # съедая ядро машины, которую сторожит. Ответы `failed` перехвачены выше.
        if [ -z "$new_ts" ]; then
            lp_fail=$((lp_fail + 1))
            [ "$lp_fail" = 1 ] && vk_log "Long Poll: в ответе нет ts — <<$(printf '%s' "$response" | head -c 120)>>"
            lp_give_up_if_dead
            if [ "$lp_fail" -ge "$LP_MAX_FAILURES" ]; then
                vk_log "Long Poll: ${lp_fail} ответов подряд без ts — переподключаюсь"
                break
            fi
            sleep "$LP_POLL_SLEEP"
            continue
        fi

        # Опрос состоялся — значит и сессия, и подключение рабочие.
        lp_last_ok="$(date +%s)"
        lp_fail=0

        if [ -n "$new_ts" ]; then
            lp_ts="$new_ts"
        fi

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

    # Сессия кончилась переподключением. Если при этом ни один опрос так и не
    # прошёл, время без удачи продолжает идти — выход решает оно.
    lp_give_up_if_dead
done
