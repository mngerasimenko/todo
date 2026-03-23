#!/bin/bash
# External monitoring: check production availability from another IP
# Runs on BACKUP server (185.244.172.45), checks PRODUCTION (todo.mngerasimenko.ru)
# Cron: */5 * * * * /root/monitoring/external-monitor.sh

source /root/monitoring/monitor.conf
DOMAIN="todo.mngerasimenko.ru"
ALERT_STATE_FILE="/tmp/external-monitor-alert-state"

send_alert() {
    local random_id=$((RANDOM * RANDOM))
    curl -s --max-time 15 -X POST "https://api.vk.com/method/messages.send" \
        -d "access_token=${VK_TOKEN}" \
        -d "peer_id=${VK_PEER_ID}" \
        -d "random_id=${random_id}" \
        --data-urlencode "message=$1" \
        -d "v=${VK_API_VERSION}" > /dev/null 2>&1
}

should_alert() {
    local key="$1"
    local file="${ALERT_STATE_FILE}_${key}"
    local now=$(date +%s)
    if [ -f "$file" ]; then
        local last=$(cat "$file")
        [ $((now - last)) -lt 1800 ] && return 1
    fi
    echo "$now" > "$file"
    return 0
}

alerts=""

# 1. Check API availability
http_code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 15 "https://${DOMAIN}/api/status" 2>/dev/null)
response_time=$(curl -s -o /dev/null -w "%{time_total}" --max-time 15 "https://${DOMAIN}/api/status" 2>/dev/null)

if [ "$http_code" != "200" ]; then
    if should_alert "external_api"; then
        alerts="${alerts}
🔴 API (external): HTTP ${http_code} (expected 200)"
    fi
fi

# 2. Response time > 10 sec
response_ms=$(echo "$response_time * 1000" | bc 2>/dev/null | cut -d. -f1)
if [ -n "$response_ms" ] && [ "$response_ms" -gt 10000 ] 2>/dev/null; then
    if should_alert "external_slow"; then
        alerts="${alerts}
⚠️ Response time: ${response_time}s (> 10s)"
    fi
fi

# 3. SSL certificate expiry < 7 days
ssl_expiry=$(echo | openssl s_client -servername "$DOMAIN" -connect "${DOMAIN}:443" 2>/dev/null | openssl x509 -noout -enddate 2>/dev/null | cut -d= -f2)
if [ -n "$ssl_expiry" ]; then
    expiry_epoch=$(date -d "$ssl_expiry" +%s 2>/dev/null)
    now_epoch=$(date +%s)
    days_left=$(( (expiry_epoch - now_epoch) / 86400 ))
    if [ "$days_left" -lt 7 ] 2>/dev/null; then
        if should_alert "external_ssl"; then
            alerts="${alerts}
⚠️ SSL: expires in ${days_left} days"
        fi
    fi
fi

# Send alert
if [ -n "$alerts" ]; then
    send_alert "🔍 External monitor: ${DOMAIN}
📅 $(date '+%Y-%m-%d %H:%M:%S')
${alerts}"
fi
