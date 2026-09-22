#!/bin/bash
# Автоматическая отправка статистики использования в VK.
#
# Строка cron на проде (редирект обоих потоков в файл обязателен: MTA на машине
# нет, и причина несостоявшейся отправки уходила бы в никуда):
#   0 4 * * * /home/deploy/todo/monitoring/stats-report.sh >> /var/log/stats-report.log 2>&1

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Конфиг не в git и на пересобранном хосте кладётся руками: без него нет учётки
# VK, и сводка просто не уйдёт. Отказ должен быть сказан вслух — не пришедшая
# сводка иначе неотличима от исправной тишины.
if [ ! -r "${SCRIPT_DIR}/monitor.conf" ]; then
    echo "stats-report: нет ${SCRIPT_DIR}/monitor.conf — учётки VK нет, сводку слать нечем" >&2
    exit 1
fi
source "${SCRIPT_DIR}/monitor.conf"
# Отправка — общая с server-monitor.sh и vk-bot.sh: токен мимо argv, ответ VK
# проверяется. Без неё сводку отправлять нечем, и молчать об этом нельзя.
if [ ! -r "${SCRIPT_DIR}/vk-send.sh" ]; then
    echo "stats-report: нет ${SCRIPT_DIR}/vk-send.sh — отправлять сводку нечем" >&2
    exit 1
fi
# shellcheck source=monitoring/vk-send.sh
source "${SCRIPT_DIR}/vk-send.sh"
# Читаемость файла — ещё не пригодность: пустой или недописанный файл
# сорсится успешно, а отправка падает с «command not found» на первом же
# алерте. Требуем саму функцию.
if ! declare -F vk_send_message >/dev/null; then
    echo "stats-report: ${SCRIPT_DIR}/vk-send.sh не дал vk_send_message — отправлять нечем" >&2
    exit 1
fi

# Файл на месте — это ещё не конфиг: пустой или недописанный сорсится успешно,
# и дальше учётка VK пуста. Сводка тогда не уйдёт, а её отсутствие неотличимо от
# исправной тишины — поэтому проверяем не читаемость файла, а сами значения.
if [ -z "${VK_TOKEN:-}" ] || [ -z "${VK_PEER_ID:-}" ]; then
    echo "stats-report: в ${SCRIPT_DIR}/monitor.conf нет учётки VK — сводку слать некому" >&2
    exit 1
fi

# Получаем статистику из Actuator
stats_json=$(docker exec todo-app wget -qO- "http://localhost:8091/actuator/usagestats/24" 2>/dev/null)

if [ -z "$stats_json" ]; then
    # Молча выйти нельзя: не пришедшая сводка неотличима от исправной тишины.
    echo "stats-report: actuator не ответил — сводка не построена и не отправлена" >&2
    exit 1
fi

msg=$(echo "$stats_json" | python3 -c "
import sys, json
d = json.load(sys.stdin)
u = d.get('users', {})
l = d.get('lists', {})
t = d.get('tasks', {})
a = d.get('activity', {})

period = d.get('period_hours', '?')
generated = d.get('generated_at', '?')
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
    # Actuator ответил, а текст не собрался — сломался разбор. Тоже отказ.
    echo "stats-report: сводка не собралась из ответа actuator — не отправлено" >&2
    exit 1
fi

vk_send_message "$msg"
