#!/bin/bash
# Автоматическая отправка статистики использования в VK
# Cron: 0 9 * * * /root/monitoring/stats-report.sh  (daily at 09:00)

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/monitor.conf"

send_vk_message() {
    local text="$1"
    local random_id=$((RANDOM * RANDOM))
    curl -s --max-time 15 -X POST "https://api.vk.com/method/messages.send" \
        -d "access_token=${VK_TOKEN}" \
        -d "peer_id=${VK_PEER_ID}" \
        -d "random_id=${random_id}" \
        --data-urlencode "message=${text}" \
        -d "v=${VK_API_VERSION}" > /dev/null 2>&1
}

# Получаем статистику из Actuator
stats_json=$(docker exec todo-app wget -qO- "http://localhost:8091/actuator/usagestats/24" 2>/dev/null)

if [ -z "$stats_json" ]; then
    exit 0
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

🔥 Активность: {a.get('active_users_last_24h', '?')} за 24ч, {a.get('active_users_last_7d', '?')} за 7д
🔗 Приглашения: {a.get('active_invite_tokens', '?')} активных''')
" 2>/dev/null)

if [ -n "$msg" ]; then
    send_vk_message "$msg"
fi
