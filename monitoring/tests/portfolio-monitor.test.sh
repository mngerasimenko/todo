#!/usr/bin/env bash
# Тесты портфельной опрашивалки. Сеть и удалённые машины не трогаются:
# curl / openssl / ssh / docker / timeout подменены заглушками из stubs/.
#
# Запуск: bash monitoring/tests/portfolio-monitor.test.sh
# Требуется: bash 4+, GNU date. jq НЕ требуется намеренно — тесты должны
# гоняться и на хосте, и в Git Bash под Windows, где jq нет.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/../portfolio-monitor.sh"
STUBS="$HERE/stubs"

# Набор безопасен, только пока заглушки перехватывают вызовы. Заглушка без бита
# исполнения молча пропускается при поиске по PATH, и тест пошёл бы в сеть: к
# настоящим сайтам, VK и ssh, а на проде — к docker.
for s in curl openssl ssh timeout docker; do
  if [ "$( PATH="$STUBS:$PATH"; command -v "$s" )" != "$STUBS/$s" ]; then
    echo "ОШИБКА: заглушка $s не перехватывает вызов (нет бита исполнения?) — набор не запускается" >&2
    exit 1
  fi
done

PASSED=0
FAILED=0
CURRENT=""
FAILED_IN_CASE=0

setup() {
  CURRENT="$1"
  FAILED_IN_CASE=0
  TMP="$(mktemp -d)"
  export STUB_STATE="$TMP"
  export STUB_VK_OUT="$TMP/vk.out"
  : > "$STUB_VK_OUT"
  mkdir -p "$TMP/state"

  TARGETS="$TMP/targets.conf"
  cat > "$TARGETS" <<'EOF'
# host|checks|http_path|expect_code
todo.keepware.ru|tls,http|/api/status|200
keepware.ru|tls||
EOF

  CONF="$TMP/monitor.conf"
  cat > "$CONF" <<EOF
ROLE=external
STATE_DIR=$TMP/state
LOG_FILE=$TMP/monitor.log
LOCK_FILE=$TMP/monitor.lock
TARGETS_FILE=$TARGETS
VK_TOKEN=vk-test-token
VK_PEER_ID=147952703
VK_API_VERSION=5.199
CERT_WARN_DAYS=21
SLOW_MS=10000
HTTP_TIMEOUT=20
TLS_TIMEOUT=15
RETRY_DELAY=0
HEARTBEAT_HOUR=99
ALERT_PREFIX=[portfolio]
SITE=teststand
PEER_TRANSPORT=none
PEER_LABEL=пир
PEER_SILENCE_SEC=1200
# Сценарии описывают поведение после первого контакта с пиром; льготный период
# проверяют отдельные тесты, выставляя его явно.
PEER_FIRST_CONTACT_GRACE_SEC=0
DISABLED_FLAG=$TMP/portfolio-monitor.disabled
EOF

  unset STUB_HTTP STUB_HTTP_todo_keepware_ru STUB_HTTP_keepware_ru \
        STUB_CERT_DAYS STUB_CERT_DAYS_todo_keepware_ru STUB_CERT_DAYS_keepware_ru \
        STUB_CERT_NAME STUB_CERT_NAME_todo_keepware_ru STUB_CERT_NAME_keepware_ru \
        STUB_WIRE_AGE_DAYS STUB_WIRE_AGE_DAYS_keepware_ru \
        STUB_DISK_AGE_DAYS STUB_DISK_AGE_DAYS_keepware_ru STUB_DISK_AGE_HOURS STUB_DISK \
        STUB_LIVE_DOMAINS STUB_LIVE_FAIL_FIRST \
        STUB_PEER STUB_PEER_BEACON STUB_PEER_READS_OK STUB_VK 2>/dev/null || true
}

teardown() { [ -n "${TMP:-}" ] && rm -rf "$TMP"; }

conf_set() { printf '%s\n' "$1" >> "$CONF"; }

run_monitor() {
  PATH="$STUBS:$PATH" PORTFOLIO_MONITOR_CONF="$CONF" bash "$SCRIPT" >/dev/null 2>&1
  RC=$?
}

vk_text() { cat "$STUB_VK_OUT"; }
vk_count() { local n; n=$(grep -c '^---$' "$STUB_VK_OUT" 2>/dev/null); [ -n "$n" ] || n=0; echo "$n"; }

pass() { [ "$FAILED_IN_CASE" = "0" ] && { PASSED=$((PASSED + 1)); printf '  ok   %s\n' "$CURRENT"; }; }
fail() { FAILED=$((FAILED + 1)); FAILED_IN_CASE=1; printf '  FAIL %s\n       %s\n' "$CURRENT" "$1"; }

assert_vk_count() {
  local want="$1" got
  got=$(vk_count)
  [ "$got" = "$want" ] || { fail "сообщений в VK: ожидали $want, получили $got — <<$(vk_text)>>"; return 1; }
  return 0
}

assert_vk_contains() {
  grep -qF -- "$1" "$STUB_VK_OUT" || { fail "в тексте VK нет «$1» — <<$(vk_text)>>"; return 1; }
  return 0
}

assert_vk_lacks() {
  grep -qF -- "$1" "$STUB_VK_OUT" && { fail "в тексте VK не должно быть «$1» — <<$(vk_text)>>"; return 1; }
  return 0
}

assert_rc() {
  [ "$RC" = "$1" ] || { fail "код возврата: ожидали $1, получили $RC"; return 1; }
  return 0
}

# Число строк отказа внутри сообщений. Считать сообщения недостаточно: код,
# теряющий один отказ из двух, всё равно шлёт ровно одно сообщение.
assert_fail_lines() {
  local want="$1" got
  got=$(grep -c '^\[X\]' "$STUB_VK_OUT" 2>/dev/null) || got=0
  [ "$got" = "$want" ] || { fail "строк отказа: ожидали $want, получили $got — <<$(vk_text)>>"; return 1; }
  return 0
}

assert_curl_was_called() {
  [ -s "$TMP/curl.argv" ] || { fail "curl вообще не вызывался — проверка вырождена"; return 1; }
  return 0
}

# Длительность, с которой ssh реально ушёл под timeout: доказательство, что кламп
# по бюджету сработал, а не только сообщение о сломанном конфиге.
assert_ssh_timeout_at_most() {
  local want="$1" why="$2" line dur
  line="$(grep -m1 ' ssh ' "$TMP/timeout.argv" 2>/dev/null)"
  if [ -z "$line" ]; then
    fail "ssh не шёл через timeout — <<$(cat "$TMP/timeout.argv" 2>/dev/null)>>"
    return 1
  fi
  dur="$(printf '%s' "$line" | awk '{print $2}')"
  case "$dur" in
    ''|*[!0-9]*) fail "не разобрал длительность таймаута из «${line}»"; return 1 ;;
    *) [ "$dur" -le "$want" ] || { fail "таймаут ssh ${dur} с, ждали не больше ${want}: ${why}"; return 1; } ;;
  esac
  return 0
}

assert_file_contains() {
  grep -qF -- "$2" "$1" 2>/dev/null || { fail "в $1 нет «$2» — <<$(cat "$1" 2>/dev/null)>>"; return 1; }
  return 0
}

# ============================================================ цели =========

t_all_green_is_silent() {
  setup "всё зелёное — ни одного сообщения, код 0"
  export STUB_CERT_DAYS=60
  run_monitor
  assert_vk_count 0 && assert_rc 0 && pass
  teardown
}

t_tls_below_threshold_alerts() {
  setup "серт в эфире ближе порога — алерт с именем домена"
  export STUB_CERT_DAYS=60
  export STUB_CERT_DAYS_keepware_ru=5
  run_monitor
  assert_vk_count 1 && assert_fail_lines 1     && assert_vk_contains "TLS keepware.ru" && assert_vk_lacks "TLS todo.keepware.ru"     && assert_rc 1 && pass
  teardown
}

t_tls_checked_for_every_target() {
  setup "TLS проверяется на каждой цели из файла, а не только на первой"
  export STUB_CERT_DAYS=60
  export STUB_CERT_DAYS_todo_keepware_ru=3
  run_monitor
  assert_vk_count 1 && assert_vk_contains "todo.keepware.ru" && pass
  teardown
}

t_http_code_alerts() {
  setup "HTTP не 200 — алерт"
  export STUB_CERT_DAYS=60
  export STUB_HTTP_todo_keepware_ru=500
  run_monitor
  assert_vk_count 1 && assert_vk_contains "500" && assert_rc 1 && pass
  teardown
}

t_slow_response_alerts() {
  setup "ответ медленнее порога — алерт, хотя код 200"
  export STUB_CERT_DAYS=60
  export STUB_HTTP_todo_keepware_ru=slow
  run_monitor
  assert_vk_count 1 && assert_vk_contains "12.500" && pass
  teardown
}

t_one_blip_is_one_message_not_two() {
  setup "сетевая яма даёт ОДНО сообщение, а не отдельное на код и на время"
  export STUB_CERT_DAYS=60
  export STUB_HTTP_todo_keepware_ru=down
  run_monitor
  assert_vk_count 1 && assert_fail_lines 1     && assert_vk_contains "HTTP 000" && assert_vk_contains "15.001" && pass
  teardown
}

t_two_failures_coalesce_into_one_message() {
  setup "два отказа в одном прогоне склеиваются в одно сообщение"
  export STUB_CERT_DAYS=60
  export STUB_HTTP_todo_keepware_ru=500
  export STUB_CERT_DAYS_keepware_ru=2
  run_monitor
  assert_vk_count 1 && assert_fail_lines 2     && assert_vk_contains "HTTP todo.keepware.ru" && assert_vk_contains "TLS keepware.ru" && pass
  teardown
}

t_antiflap_single_dip_is_silent() {
  setup "антифлап: первый опрос упал, повтор прошёл — тишина"
  export STUB_CERT_DAYS=60
  export STUB_HTTP_todo_keepware_ru=flap
  run_monitor
  assert_vk_count 0 && assert_rc 0 && pass
  teardown
}

t_unreadable_targets_file_is_loud() {
  setup "нечитаемый файл целей — опрашивалка кричит, а не молчит зелёным"
  export STUB_CERT_DAYS=60
  rm -f "$TARGETS"
  run_monitor
  assert_vk_count 1 && assert_vk_contains "целей" && assert_rc 1 && pass
  teardown
}

t_empty_targets_file_is_loud() {
  setup "пустой файл целей — тоже отказ, а не зелёная тишина"
  export STUB_CERT_DAYS=60
  : > "$TARGETS"
  run_monitor
  assert_vk_count 1 && assert_rc 1 && pass
  teardown
}

# ================================================= дисциплина шума =========

t_repeat_failure_is_silent() {
  setup "повторный прогон при том же отказе — тишина (сигнал на смену состояния)"
  export STUB_CERT_DAYS=60
  export STUB_HTTP_todo_keepware_ru=500
  run_monitor
  run_monitor
  assert_vk_count 1 && pass
  teardown
}

t_recovery_sends_one_message() {
  setup "восстановление — одно сообщение с длительностью простоя"
  export STUB_CERT_DAYS=60
  export STUB_HTTP_todo_keepware_ru=500
  run_monitor
  unset STUB_HTTP_todo_keepware_ru
  run_monitor
  assert_vk_count 2 && assert_vk_contains "Восстановлено" && assert_rc 0 && pass
  teardown
}

t_failed_send_is_not_deduped_away() {
  setup "VK отверг отправку — состояние не фиксируем, следующий прогон шлёт снова"
  export STUB_CERT_DAYS=60
  export STUB_HTTP_todo_keepware_ru=500
  export STUB_VK=reject
  run_monitor
  export STUB_VK=ok
  run_monitor
  assert_vk_count 2 && pass
  teardown
}

t_no_secret_in_argv() {
  setup "токен VK не попадает в командную строку curl"
  export STUB_CERT_DAYS=60
  export STUB_HTTP_todo_keepware_ru=500
  run_monitor
  assert_curl_was_called || { teardown; return; }
  if grep -q "vk-test-token" "$TMP/curl.argv"; then
    fail "токен найден в argv curl: $(grep -m1 vk-test-token "$TMP/curl.argv")"
  fi
  pass
  teardown
}

t_state_survives_outside_tmp() {
  setup "состояние пишется в STATE_DIR, а не в /tmp опрашивалки"
  export STUB_CERT_DAYS=60
  export STUB_HTTP_todo_keepware_ru=500
  run_monitor
  if ! ls "$TMP/state"/fail.* >/dev/null 2>&1; then
    fail "в STATE_DIR нет ни одного файла состояния — <<$(ls -a "$TMP/state")>>"
  fi
  pass
  teardown
}

t_run_is_logged() {
  setup "каждый прогон оставляет строку в логе — история срабатываний"
  export STUB_CERT_DAYS=60
  run_monitor
  assert_file_contains "$TMP/monitor.log" "RUN" && pass
  teardown
}

t_heartbeat_when_due() {
  setup "суточный пульс уходит, когда пришёл его час"
  export STUB_CERT_DAYS=60
  conf_set "HEARTBEAT_HOUR=0"
  run_monitor
  assert_vk_count 1 && assert_vk_contains "пульс" && pass
  teardown
}

# ================================================ взаимный дед-мэн =========

t_beacon_written_at_end_of_run() {
  setup "маячок пишется в конце прогона — он знает про отказы этого прогона"
  export STUB_CERT_DAYS=60
  export STUB_HTTP_todo_keepware_ru=500
  run_monitor
  # fails=1 может появиться в маячке только после того, как проверки отработали.
  assert_file_contains "$TMP/state/beacon.self" "ts="     && assert_file_contains "$TMP/state/beacon.self" "fails=1" && pass
  teardown
}

t_peer_fresh_beacon_is_silent() {
  setup "маячок пира свежий — тишина"
  export STUB_CERT_DAYS=60
  conf_set "PEER_TRANSPORT=local"
  printf 'ts=%s host=peer channel=ok fails=0\n' "$(date +%s)" > "$TMP/state/peer.beacon"
  run_monitor
  assert_vk_count 0 && assert_rc 0 && pass
  teardown
}

t_peer_silence_alerts() {
  setup "пир молчит дольше порога — алерт (главная дырка, которую закрываем)"
  export STUB_CERT_DAYS=60
  conf_set "PEER_TRANSPORT=local"
  printf 'ts=%s host=peer channel=ok fails=0\n' "$(( $(date +%s) - 3600 ))" > "$TMP/state/peer.beacon"
  run_monitor
  assert_vk_count 1 && assert_vk_contains "пир" && assert_rc 1 && pass
  teardown
}

t_missing_peer_beacon_alerts() {
  setup "маячка пира нет вовсе — алерт, а не зелёная тишина"
  export STUB_CERT_DAYS=60
  conf_set "PEER_TRANSPORT=local"
  run_monitor
  assert_vk_count 1 && assert_vk_contains "не читается" && assert_rc 1 && pass
  teardown
}

t_peer_unreachable_alerts() {
  setup "пир недоступен по ssh — алерт"
  export STUB_CERT_DAYS=60
  conf_set "PEER_TRANSPORT=ssh"
  conf_set "PEER_HOST=198.51.100.7"
  conf_set "PEER_SSH_KEY=$TMP/fake.key"
  export STUB_PEER=unreachable
  run_monitor
  assert_vk_count 1 && assert_vk_contains "не встало ssh-соединение" && assert_rc 1 && pass
  teardown
}

t_peer_beacon_pulled_over_ssh() {
  setup "маячок пира читается по ssh; свежий — тишина"
  export STUB_CERT_DAYS=60
  conf_set "PEER_TRANSPORT=ssh"
  conf_set "PEER_HOST=198.51.100.7"
  conf_set "PEER_SSH_KEY=$TMP/fake.key"
  export STUB_PEER_BEACON="ts=$(date +%s) host=peer channel=ok fails=0"
  run_monitor
  assert_vk_count 0 && assert_rc 0 && pass
  teardown
}

t_own_beacon_pushed_to_peer() {
  setup "свой маячок доставляется пиру по ssh"
  export STUB_CERT_DAYS=60
  conf_set "PEER_TRANSPORT=ssh"
  conf_set "PEER_HOST=198.51.100.7"
  conf_set "PEER_SSH_KEY=$TMP/fake.key"
  export STUB_PEER_BEACON="ts=$(date +%s) host=peer channel=ok fails=0"
  run_monitor
  assert_file_contains "$TMP/ssh.push" "ts=" && pass
  teardown
}

t_push_failure_alerts() {
  setup "маячок не доставился пиру — алерт: иначе пир решит, что мы умерли"
  export STUB_CERT_DAYS=60
  conf_set "PEER_TRANSPORT=ssh"
  conf_set "PEER_HOST=198.51.100.7"
  conf_set "PEER_SSH_KEY=$TMP/fake.key"
  export STUB_PEER_BEACON="ts=$(date +%s) host=peer channel=ok fails=0"
  export STUB_PEER=push_fails
  run_monitor
  assert_vk_count 1 && assert_rc 1 && pass
  teardown
}

t_peer_broken_channel_alerts() {
  setup "пир сообщает, что его канал алертов сломан — сигналим со своей стороны"
  export STUB_CERT_DAYS=60
  conf_set "PEER_TRANSPORT=local"
  printf 'ts=%s host=peer channel=fail fails=0\n' "$(date +%s)" > "$TMP/state/peer.beacon"
  run_monitor
  assert_vk_count 1 && assert_vk_contains "канал" && assert_rc 1 && pass
  teardown
}

t_peer_recovery_message() {
  setup "пир снова заговорил — сообщение о восстановлении"
  export STUB_CERT_DAYS=60
  conf_set "PEER_TRANSPORT=local"
  printf 'ts=%s host=peer channel=ok fails=0\n' "$(( $(date +%s) - 3600 ))" > "$TMP/state/peer.beacon"
  run_monitor
  printf 'ts=%s host=peer channel=ok fails=0\n' "$(date +%s)" > "$TMP/state/peer.beacon"
  run_monitor
  assert_vk_count 2 && assert_vk_contains "Восстановлено" && pass
  teardown
}

t_garbled_peer_beacon_alerts() {
  setup "маячок пира не разбирается — алерт, а не молчаливое «свежий»"
  export STUB_CERT_DAYS=60
  conf_set "PEER_TRANSPORT=local"
  printf 'мусор\n' > "$TMP/state/peer.beacon"
  run_monitor
  assert_vk_count 1 && assert_rc 1 && pass
  teardown
}

t_beacon_channel_reflects_send_failure() {
  setup "провалившаяся отправка отражается в собственном маячке как channel=fail"
  export STUB_CERT_DAYS=60
  export STUB_HTTP_todo_keepware_ru=500
  export STUB_VK=reject
  run_monitor
  assert_file_contains "$TMP/state/beacon.self" "channel=fail" && pass
  teardown
}

# ============================== расхождение «эфир против диска» ===========

t_cert_drift_alerts() {
  setup "на диске серт свежее, чем в эфире — nginx не перечитал (класс инцидента replyAI)"
  export STUB_CERT_DAYS=60
  conf_set "CERT_DRIFT_DOMAINS=todo.keepware.ru"
  export STUB_WIRE_AGE_DAYS=40
  export STUB_DISK_AGE_DAYS=1
  run_monitor
  assert_vk_count 1 && assert_vk_contains "todo.keepware.ru" && assert_rc 1 && pass
  teardown
}

t_cert_no_drift_is_silent() {
  setup "эфир и диск совпадают — тишина"
  export STUB_CERT_DAYS=60
  conf_set "CERT_DRIFT_DOMAINS=todo.keepware.ru"
  export STUB_WIRE_AGE_DAYS=3
  export STUB_DISK_AGE_DAYS=3
  run_monitor
  assert_vk_count 0 && assert_rc 0 && pass
  teardown
}

t_cert_drift_unreadable_disk_is_loud() {
  setup "серт с диска не читается — проверка кричит, а не считается пройденной"
  export STUB_CERT_DAYS=60
  conf_set "CERT_DRIFT_DOMAINS=todo.keepware.ru"
  export STUB_DISK=missing
  run_monitor
  assert_vk_count 1 && assert_rc 1 && pass
  teardown
}

# ============================ разбор файла целей ===========================

t_unknown_check_token_is_loud() {
  setup "опечатка в поле checks — опрашивалка кричит, а не пропускает цель молча"
  export STUB_CERT_DAYS=60
  printf 'todo.keepware.ru|tsl||\n' > "$TARGETS"
  run_monitor
  assert_vk_count 1 && assert_vk_contains "tsl" && assert_rc 1 && pass
  teardown
}

t_checks_are_normalized() {
  setup "пробел и регистр в checks не отключают проверку"
  export STUB_CERT_DAYS=60
  export STUB_HTTP_todo_keepware_ru=500
  printf 'todo.keepware.ru|TLS, HTTP|/api/status|200\n' > "$TARGETS"
  run_monitor
  assert_vk_count 1 && assert_vk_contains "500" && pass
  teardown
}

t_targets_with_crlf_and_short_lines() {
  setup "CRLF и строки из двух полей — цели всё равно проверяются"
  export STUB_CERT_DAYS=60
  export STUB_CERT_DAYS_keepware_ru=3
  printf 'todo.keepware.ru|tls\r\nkeepware.ru|tls\r\n' > "$TARGETS"
  run_monitor
  assert_vk_count 1 && assert_vk_contains "keepware.ru" && assert_rc 1 && pass
  teardown
}

t_external_role_without_targets_is_loud() {
  setup "роль external без файла целей — вечный зелёный no-op недопустим"
  export STUB_CERT_DAYS=60
  grep -v '^TARGETS_FILE=' "$CONF" > "$CONF.tmp" && mv "$CONF.tmp" "$CONF"
  run_monitor
  assert_vk_count 1 && assert_rc 1 && pass
  teardown
}

t_state_keys_do_not_collide() {
  setup "цели, различающиеся только точкой и дефисом, не делят файл состояния"
  export STUB_CERT_DAYS=2
  printf 'a.b.keepware.ru|tls||\na-b.keepware.ru|tls||\n' > "$TARGETS"
  run_monitor
  local n
  n=$(ls "$TMP/state" | grep -c '^fail\.' 2>/dev/null) || n=0
  [ "$n" -ge 2 ] || fail "ожидали два разных файла состояния, нашли $n — <<$(ls "$TMP/state")>>"
  pass
  teardown
}

# ============================ устойчивость ================================

t_future_beacon_is_not_green() {
  setup "маячок пира из будущего — разъехались часы, дед-мэн обязан кричать"
  export STUB_CERT_DAYS=60
  conf_set "PEER_TRANSPORT=local"
  printf 'ts=%s host=peer channel=ok fails=0\n' "$(( $(date +%s) + 86400 ))" > "$TMP/state/peer.beacon"
  run_monitor
  assert_vk_count 1 && assert_rc 1 && pass
  teardown
}

t_peer_death_is_not_channel_recovery() {
  setup "пир умер при сломанном канале — не выдавать это за восстановление канала"
  export STUB_CERT_DAYS=60
  conf_set "PEER_TRANSPORT=local"
  printf 'ts=%s host=peer channel=fail fails=0\n' "$(date +%s)" > "$TMP/state/peer.beacon"
  run_monitor
  rm -f "$TMP/state/peer.beacon"
  run_monitor
  assert_vk_lacks "Восстановлено" && pass
  teardown
}

t_unwritable_state_dir_is_loud() {
  setup "STATE_DIR недоступен — опрашивалка кричит, а канал алертов не умирает вместе с ним"
  export STUB_CERT_DAYS=60
  : > "$TMP/blocker"
  grep -v '^STATE_DIR=' "$CONF" > "$CONF.tmp" && mv "$CONF.tmp" "$CONF"
  conf_set "STATE_DIR=$TMP/blocker/state"
  run_monitor
  assert_vk_count 1 && assert_rc 1 && pass
  teardown
}

t_curl_failure_on_send_is_not_deduped() {
  setup "curl упал при отправке — состояние не фиксируем, следующий прогон шлёт снова"
  export STUB_CERT_DAYS=60
  export STUB_HTTP_todo_keepware_ru=500
  export STUB_VK=curlfail
  run_monitor
  export STUB_VK=ok
  run_monitor
  assert_vk_count 1 && pass
  teardown
}

t_token_never_reaches_the_log() {
  setup "токен VK не попадает в лог"
  export STUB_CERT_DAYS=60
  export STUB_HTTP_todo_keepware_ru=500
  export STUB_VK=reject
  run_monitor
  grep -q "vk-test-token" "$TMP/monitor.log" && fail "токен найден в логе: $(grep -m1 vk-test-token "$TMP/monitor.log")"
  pass
  teardown
}

t_tls_handshake_failure_alerts() {
  setup "рукопожатие не состоялось — главный отказ, хост мёртв"
  export STUB_CERT_DAYS=none
  printf 'todo.keepware.ru|tls||\n' > "$TARGETS"
  run_monitor
  assert_vk_count 1 && assert_vk_contains "не отдало сертификат" && assert_rc 1 && pass
  teardown
}

# ================= расхождение копии скриптов с репозиторием ===============

t_copy_drift_alerts() {
  setup "установленная копия разошлась с репозиторием — сигнал, а не тихий дрейф"
  export STUB_CERT_DAYS=60
  mkdir -p "$TMP/installed" "$TMP/repo"
  printf 'echo same\n' > "$TMP/installed/certbot-renew.sh"
  printf 'echo same\n' > "$TMP/repo/certbot-renew.sh"
  printf 'echo old\n' > "$TMP/installed/portfolio-monitor.sh"
  printf 'echo new\n' > "$TMP/repo/portfolio-monitor.sh"
  conf_set "COPY_DRIFT_CHECK=$TMP/installed:$TMP/repo"
  run_monitor
  assert_vk_count 1 && assert_vk_contains "portfolio-monitor.sh" && assert_vk_lacks "certbot-renew.sh" && assert_rc 1 && pass
  teardown
}

t_copy_in_sync_is_silent() {
  setup "копия совпадает с репозиторием — тишина"
  export STUB_CERT_DAYS=60
  mkdir -p "$TMP/installed" "$TMP/repo"
  for f in portfolio-monitor.sh certbot-renew.sh; do
    printf 'echo same\n' > "$TMP/installed/$f"
    printf 'echo same\n' > "$TMP/repo/$f"
  done
  conf_set "COPY_DRIFT_CHECK=$TMP/installed:$TMP/repo"
  run_monitor
  assert_vk_count 0 && assert_rc 0 && pass
  teardown
}

t_copy_drift_ignores_scripts_not_installed_by_us() {
  setup "старые скрипты рядом с копией не сверяются — иначе вечный красный с первого прогона"
  export STUB_CERT_DAYS=60
  mkdir -p "$TMP/installed" "$TMP/repo"
  for f in portfolio-monitor.sh certbot-renew.sh; do
    printf 'echo same\n' > "$TMP/installed/$f"
    printf 'echo same\n' > "$TMP/repo/$f"
  done
  printf 'echo old\n' > "$TMP/installed/server-monitor.sh"
  printf 'echo new\n' > "$TMP/repo/server-monitor.sh"
  conf_set "COPY_DRIFT_CHECK=$TMP/installed:$TMP/repo"
  run_monitor
  assert_vk_count 0 && assert_rc 0 && pass
  teardown
}

t_copy_drift_missing_copy_is_loud() {
  setup "установленной копии нет — сигнал: cron исполняет файл, которого нет"
  export STUB_CERT_DAYS=60
  mkdir -p "$TMP/installed" "$TMP/repo"
  printf 'echo same\n' > "$TMP/repo/portfolio-monitor.sh"
  printf 'echo same\n' > "$TMP/repo/certbot-renew.sh"
  conf_set "COPY_DRIFT_CHECK=$TMP/installed:$TMP/repo"
  run_monitor
  assert_vk_count 1 && assert_vk_contains "portfolio-monitor.sh" && assert_rc 1 && pass
  teardown
}

# ================= бюджет прогона и порядок отправки =======================

t_target_alert_goes_before_peer_ops() {
  setup "алерт по целям уходит ДО операций с пиром — зависший пир его не задерживает"
  export STUB_CERT_DAYS=60
  export STUB_HTTP_todo_keepware_ru=500
  conf_set "PEER_TRANSPORT=ssh"
  conf_set "PEER_HOST=198.51.100.7"
  conf_set "PEER_SSH_KEY=$TMP/fake.key"
  export STUB_PEER_BEACON="ts=$(date +%s) host=peer channel=ok fails=0"
  run_monitor
  local first
  first="$(head -1 "$TMP/events" 2>/dev/null)"
  [ "$first" = "vk" ] || fail "первым событием ждали отправку в VK, а было «${first}» — <<$(tr '\n' ' ' < "$TMP/events" 2>/dev/null)>>"
  pass
  teardown
}

t_peer_ssh_timeout_is_clamped_to_run_budget() {
  setup "таймаут ssh урезан по остатку бюджета прогона и идёт с --foreground"
  export STUB_CERT_DAYS=60
  conf_set "PEER_TRANSPORT=ssh"
  conf_set "PEER_HOST=198.51.100.7"
  conf_set "PEER_SSH_KEY=$TMP/fake.key"
  conf_set "PEER_SSH_TIMEOUT=100"
  conf_set "RUN_BUDGET_SEC=100"
  conf_set "SEND_RESERVE_SEC=40"
  export STUB_PEER_BEACON="ts=$(date +%s) host=peer channel=ok fails=0"
  run_monitor
  local line dur
  line="$(grep -m1 ' ssh ' "$TMP/timeout.argv" 2>/dev/null)"
  if [ -z "$line" ]; then
    fail "ssh не шёл через timeout — <<$(cat "$TMP/timeout.argv" 2>/dev/null)>>"
    teardown
    return
  fi
  case "$line" in
    "--foreground "*) ;;
    *) fail "timeout для ssh без --foreground: «${line}»" ;;
  esac
  dur="$(printf '%s' "$line" | awk '{print $2}')"
  case "$dur" in
    ''|*[!0-9]*) fail "не разобрал длительность таймаута из «${line}»" ;;
    *) [ "$dur" -le 60 ] || fail "таймаут ssh ${dur} с не урезан по бюджету (ждали не больше 60)" ;;
  esac
  pass
  teardown
}

t_exhausted_budget_is_loud_and_skips_peer() {
  setup "бюджет прогона исчерпан — к пиру не ходим и сообщаем об этом, а не молчим зелёным"
  export STUB_CERT_DAYS=60
  conf_set "PEER_TRANSPORT=ssh"
  conf_set "PEER_HOST=198.51.100.7"
  conf_set "PEER_SSH_KEY=$TMP/fake.key"
  conf_set "RUN_BUDGET_SEC=1"
  conf_set "SEND_RESERVE_SEC=0"
  export STUB_PEER_BEACON="ts=$(date +%s) host=peer channel=ok fails=0"
  run_monitor
  [ -s "$TMP/ssh.argv" ] && fail "ssh вызывался при исчерпанном бюджете — <<$(cat "$TMP/ssh.argv")>>"
  assert_vk_contains "бюджет" && assert_rc 1 && pass
  teardown
}

# ================= льготный период до первого контакта с пиром =============

t_peer_never_seen_is_log_only_in_grace() {
  setup "пир ещё ни разу не выходил на связь — в льготный период только лог, без тревоги"
  export STUB_CERT_DAYS=60
  conf_set "PEER_TRANSPORT=local"
  conf_set "PEER_FIRST_CONTACT_GRACE_SEC=86400"
  run_monitor
  assert_vk_count 0 && assert_rc 0 && assert_file_contains "$TMP/monitor.log" "GRACE" && pass
  teardown
}

t_peer_never_seen_after_grace_alerts() {
  setup "льготный период истёк, а пир так и не вышел на связь — тревога"
  export STUB_CERT_DAYS=60
  conf_set "PEER_TRANSPORT=local"
  conf_set "PEER_FIRST_CONTACT_GRACE_SEC=600"
  printf '%s\n' "$(( $(date +%s) - 3600 ))" > "$TMP/state/peer.first_run"
  run_monitor
  assert_vk_count 1 && assert_vk_contains "ни разу" && assert_rc 1 && pass
  teardown
}

t_grace_ends_at_first_contact() {
  setup "пир однажды вышел на связь — дальше его молчание сразу тревога, без льготы"
  export STUB_CERT_DAYS=60
  conf_set "PEER_TRANSPORT=local"
  conf_set "PEER_FIRST_CONTACT_GRACE_SEC=86400"
  printf 'ts=%s host=peer channel=ok fails=0\n' "$(date +%s)" > "$TMP/state/peer.beacon"
  run_monitor
  printf 'ts=%s host=peer channel=ok fails=0\n' "$(( $(date +%s) - 3600 ))" > "$TMP/state/peer.beacon"
  run_monitor
  assert_vk_count 1 && assert_vk_contains "молчит" && assert_rc 1 && pass
  teardown
}

t_push_failure_alerts_even_in_grace() {
  setup "в льготный период маячок не доставился — тревога сразу: сломан наш ключ, а не «пир ещё не встал»"
  export STUB_CERT_DAYS=60
  conf_set "PEER_TRANSPORT=ssh"
  conf_set "PEER_HOST=198.51.100.7"
  conf_set "PEER_SSH_KEY=$TMP/fake.key"
  conf_set "PEER_FIRST_CONTACT_GRACE_SEC=86400"
  export STUB_PEER=unreachable
  run_monitor
  assert_vk_count 1 && assert_vk_contains "Доставка маячка" && assert_rc 1 && pass
  teardown
}

# ================= канал алертов: одно чтение и исходящие ==================

t_blinking_second_read_is_not_recovery() {
  setup "моргнувшее соединение посреди прогона не выдаётся за «Восстановлено» канала пира"
  export STUB_CERT_DAYS=60
  conf_set "PEER_TRANSPORT=ssh"
  conf_set "PEER_HOST=198.51.100.7"
  conf_set "PEER_SSH_KEY=$TMP/fake.key"
  export STUB_PEER_BEACON="ts=$(date +%s) host=peer channel=fail fails=0"
  run_monitor
  rm -f "$TMP/calls.ssh_read"
  export STUB_PEER_READS_OK=1
  export STUB_PEER_BEACON="ts=$(date +%s) host=peer channel=fail fails=0"
  run_monitor
  assert_vk_count 1 && assert_vk_lacks "Восстановлено" && pass
  teardown
}

# Текст последнего сообщения. Заглушка пишет и отвергнутые отправки, поэтому то, что
# владелец увидел последним, — это последнее сообщение, а не весь файл.
vk_last() { awk '/^---$/ { last = cur; cur = ""; next } { cur = cur $0 "\n" } END { printf "%s", last }' "$STUB_VK_OUT"; }

# Последнюю попытку отправки — на час назад: пробная отправка идёт не чаще раза в
# 30 минут, и без сдвига сценарий упёрся бы в это ограничение.
back_date_channel_down() {
  local since count last
  read -r since count last < "$TMP/state/channel.down"
  printf '%s %s %s\n' "$since" "$count" "$(( $(date +%s) - 3600 ))" > "$TMP/state/channel.down"
}

t_broken_channel_is_probed_and_reports_the_gap() {
  setup "отправка сорвалась, а слать потом нечего — пробная отправка сообщает о пропуске, канал снова ok"
  export STUB_CERT_DAYS=60
  export STUB_HTTP_todo_keepware_ru=500
  export STUB_VK=reject
  run_monitor
  back_date_channel_down
  unset STUB_HTTP_todo_keepware_ru
  export STUB_VK=ok
  run_monitor
  assert_vk_count 2 || { teardown; return; }
  vk_last | grep -qF "не доставлял" || fail "пробное сообщение не говорит о пропуске — <<$(vk_last)>>"
  assert_file_contains "$TMP/state/beacon.self" "channel=ok" || { teardown; return; }
  # Пропуск доложен: третий прогон молчит.
  run_monitor
  assert_vk_count 2 && pass
  teardown
}

t_undelivered_recovery_is_not_claimed_while_target_is_down_again() {
  setup "восстановление не доставилось, цель снова лежит — ложного «Восстановлено» владелец не получает"
  export STUB_CERT_DAYS=60
  export STUB_HTTP_todo_keepware_ru=500
  run_monitor
  unset STUB_HTTP_todo_keepware_ru
  export STUB_VK=reject
  run_monitor
  back_date_channel_down
  # Заглушка пишет и отвергнутые отправки — смотрим только то, что ушло в третьем прогоне.
  local before sent
  before=$(wc -c < "$STUB_VK_OUT" | tr -d ' ')
  export STUB_HTTP_todo_keepware_ru=500
  export STUB_VK=ok
  run_monitor
  sent="$(tail -c +$(( before + 1 )) "$STUB_VK_OUT")"
  case "$sent" in *"Восстановлено"*) fail "владельцу ушло «Восстановлено», хотя цель лежит — <<${sent}>>" ;; esac
  case "$sent" in *"не доставлял"*) ;; *) fail "пробная отправка о пропуске не ушла — <<${sent}>>" ;; esac
  pass
  teardown
}

t_first_send_failure_is_retried_in_run_without_later_duplicate() {
  setup "первая отправка прогона сорвалась, вторая прошла — следующий прогон не повторяет тот же алерт"
  export STUB_CERT_DAYS=60
  export STUB_HTTP_todo_keepware_ru=500
  conf_set "PEER_TRANSPORT=local"
  printf 'ts=%s host=peer channel=ok fails=0\n' "$(( $(date +%s) - 3600 ))" > "$TMP/state/peer.beacon"
  export STUB_VK=reject_first
  run_monitor
  export STUB_VK=ok
  run_monitor
  assert_vk_count 2 || { teardown; return; }
  vk_last | grep -qF "HTTP todo.keepware.ru" || fail "отказ цели не дошёл второй отправкой — <<$(vk_last)>>"
  pass
  teardown
}

t_empty_first_run_stamp_does_not_extend_grace_forever() {
  setup "пустой peer.first_run не продлевает льготу навсегда — без даты льгота считается истёкшей"
  export STUB_CERT_DAYS=60
  conf_set "PEER_TRANSPORT=local"
  conf_set "PEER_FIRST_CONTACT_GRACE_SEC=600"
  : > "$TMP/state/peer.first_run"
  run_monitor
  assert_vk_count 1 && assert_vk_contains "ни разу" && assert_rc 1 && pass
  teardown
}

t_peer_seen_once_ends_grace_even_if_beacon_vanishes() {
  setup "пир однажды вышел на связь, потом маячок пропал — тревога сразу, льгота не возвращается"
  export STUB_CERT_DAYS=60
  conf_set "PEER_TRANSPORT=local"
  conf_set "PEER_FIRST_CONTACT_GRACE_SEC=86400"
  printf 'ts=%s host=peer channel=ok fails=0\n' "$(date +%s)" > "$TMP/state/peer.beacon"
  run_monitor
  rm -f "$TMP/state/peer.beacon"
  run_monitor
  assert_vk_count 1 && assert_vk_contains "не читается" && assert_rc 1 && pass
  teardown
}

t_garbage_budget_is_loud() {
  setup "нечисловой бюджет прогона — опрашивалка сообщает о сломанном конфиге, а не молча выключает бюджет"
  export STUB_CERT_DAYS=60
  conf_set "RUN_BUDGET_SEC=260s"
  run_monitor
  assert_vk_count 1 && assert_vk_contains "RUN_BUDGET_SEC" && assert_rc 1 && pass
  teardown
}

t_disk_cert_read_is_bounded_by_timeout() {
  setup "чтение серта с диска через docker exec урезано бюджетом: зависший dockerd не убивает прогон"
  export STUB_CERT_DAYS=60
  conf_set "CERT_DRIFT_DOMAINS=todo.keepware.ru"
  export STUB_WIRE_AGE_DAYS=3
  export STUB_DISK_AGE_DAYS=3
  run_monitor
  grep -qE -- '^--foreground [0-9]+ docker exec' "$TMP/timeout.argv" 2>/dev/null \
    || fail "docker exec идёт мимо timeout — <<$(cat "$TMP/timeout.argv" 2>/dev/null)>>"
  pass
  teardown
}

t_disabled_flag_stops_the_run() {
  setup "выключатель: опрашивалка выходит сразу — ни сообщений, ни маячка"
  export STUB_CERT_DAYS=60
  export STUB_HTTP_todo_keepware_ru=500
  : > "$TMP/portfolio-monitor.disabled"
  run_monitor
  assert_vk_count 0 && assert_rc 0 && assert_file_contains "$TMP/monitor.log" "DISABLED" || { teardown; return; }
  [ -f "$TMP/state/beacon.self" ] && fail "выключенная опрашивалка обновила маячок — сосед не узнал бы, что она не работает"
  pass
  teardown
}

t_drift_domains_auto_come_from_certbot() {
  setup "CERT_DRIFT_DOMAINS=auto — сверяются все серты certbot, в том числе появившиеся после установки"
  export STUB_CERT_DAYS=60
  conf_set "CERT_DRIFT_DOMAINS=auto"
  export STUB_LIVE_DOMAINS="todo.keepware.ru keepware.ru"
  export STUB_WIRE_AGE_DAYS_keepware_ru=40
  export STUB_DISK_AGE_DAYS_keepware_ru=1
  run_monitor
  assert_vk_count 1 && assert_vk_contains "Эфир против диска keepware.ru" && assert_rc 1 || { teardown; return; }
  # README из live/ — не серт: сверка по нему не должна даже запускаться. Проверка по
  # тексту алерта ничего бы не доказала — сверка README прошла бы молча.
  grep -q 'live/README/' "$TMP/docker.argv" 2>/dev/null && fail "README из live/ попал в сверку — <<$(cat "$TMP/docker.argv")>>"
  pass
  teardown
}

t_drift_domains_auto_listing_failure_is_loud() {
  setup "список сертов certbot не читается — сверка не молчит зелёным"
  export STUB_CERT_DAYS=60
  conf_set "CERT_DRIFT_DOMAINS=auto"
  export STUB_LIVE_DOMAINS=fail
  run_monitor
  assert_vk_count 1 && assert_vk_contains "не смог перечислить" && assert_rc 1 && pass
  teardown
}

t_fresh_renewal_waiting_for_reload_is_not_drift() {
  setup "серт продлён час назад, reload ещё впереди — это не дрейф, тишина"
  export STUB_CERT_DAYS=60
  conf_set "CERT_DRIFT_DOMAINS=todo.keepware.ru"
  export STUB_WIRE_AGE_DAYS=40
  export STUB_DISK_AGE_HOURS=1
  run_monitor
  assert_vk_count 0 && assert_rc 0 && pass
  teardown
}

t_stale_certbot_renew_ok_stamp_alerts() {
  setup "certbot-renew.sh давно не отрабатывал успешно — тревога: сломан reload или пропал cron"
  export STUB_CERT_DAYS=60
  conf_set "RENEW_OK_FILE=$TMP/state/certbot-renew.ok"
  printf '%s\n' "$(( $(date +%s) - 5 * 3600 ))" > "$TMP/state/certbot-renew.ok"
  run_monitor
  assert_vk_count 1 && assert_vk_contains "certbot-renew" && assert_rc 1 && pass
  teardown
}

t_fresh_certbot_renew_ok_stamp_is_silent() {
  setup "certbot-renew.sh отработал успешно час назад — тишина"
  export STUB_CERT_DAYS=60
  conf_set "RENEW_OK_FILE=$TMP/state/certbot-renew.ok"
  printf '%s\n' "$(( $(date +%s) - 3600 ))" > "$TMP/state/certbot-renew.ok"
  run_monitor
  assert_vk_count 0 && assert_rc 0 && pass
  teardown
}

t_missing_certbot_renew_ok_stamp_alerts() {
  setup "отметки certbot-renew нет — тревога, а не зелёная тишина"
  export STUB_CERT_DAYS=60
  conf_set "RENEW_OK_FILE=$TMP/state/certbot-renew.ok"
  run_monitor
  assert_vk_count 1 && assert_vk_contains "certbot-renew" && assert_rc 1 && pass
  teardown
}

t_huge_garbled_beacon_does_not_overflow_the_message() {
  setup "огромный мусорный маячок пира не раздувает сообщение за лимит VK"
  export STUB_CERT_DAYS=60
  conf_set "PEER_TRANSPORT=local"
  head -c 6000 /dev/zero | tr '\0' 'x' > "$TMP/state/peer.beacon"
  run_monitor
  assert_vk_count 1 || { teardown; return; }
  local len
  len=$(vk_last | wc -c | tr -d ' ')
  [ "$len" -lt 4000 ] || fail "сообщение ${len} байт — VK отвергнет его, и все следующие алерты застрянут"
  pass
  teardown
}

t_heartbeat_with_failures_keeps_grace_note() {
  setup "пульс при отказах всё равно показывает, что пир ещё не выходил на связь"
  export STUB_CERT_DAYS=60
  export STUB_HTTP_todo_keepware_ru=500
  conf_set "PEER_TRANSPORT=local"
  conf_set "PEER_FIRST_CONTACT_GRACE_SEC=86400"
  conf_set "HEARTBEAT_HOUR=0"
  run_monitor
  vk_last | grep -qF "пульс" || { fail "последним ушёл не пульс — <<$(vk_last)>>"; teardown; return; }
  vk_last | grep -qF "ни разу не выходил" || fail "в пульсе нет строки льготы — <<$(vk_last)>>"
  pass
  teardown
}

t_drift_list_blip_is_retried() {
  setup "docker один раз не отдал список сертов — перечитываем, а не шлём пару «отказ / Восстановлено»"
  export STUB_CERT_DAYS=60
  conf_set "CERT_DRIFT_DOMAINS=auto"
  export STUB_LIVE_DOMAINS="todo.keepware.ru"
  export STUB_LIVE_FAIL_FIRST=1
  run_monitor
  unset STUB_LIVE_FAIL_FIRST
  assert_vk_count 0 && assert_rc 0 && pass
  teardown
}

t_peer_role_enables_prod_checks_by_default() {
  setup "роль peer без ключей в конфиге — сверка сертов и отметка продления всё равно включены"
  export STUB_CERT_DAYS=60
  conf_set "ROLE=peer"
  export STUB_LIVE_DOMAINS="todo.keepware.ru"
  run_monitor
  assert_vk_contains "certbot-renew" || { teardown; return; }
  grep -q 'live/todo.keepware.ru/cert.pem' "$TMP/docker.argv" 2>/dev/null \
    || fail "сверка «эфир против диска» на роли peer не выполнялась — <<$(cat "$TMP/docker.argv" 2>/dev/null)>>"
  pass
  teardown
}

t_drift_grace_does_not_fake_recovery() {
  setup "дрейф уже в отказе, серт перевыпустили — льгота не шлёт ложное «Восстановлено», nginx всё ещё старый"
  export STUB_CERT_DAYS=60
  conf_set "CERT_DRIFT_DOMAINS=todo.keepware.ru"
  printf '%s\n' "$(( $(date +%s) - 7200 ))" > "$TMP/state/fail.drift_c_todo_d_keepware_d_ru"
  export STUB_WIRE_AGE_DAYS=40
  export STUB_DISK_AGE_HOURS=1
  run_monitor
  assert_vk_lacks "Восстановлено" && assert_rc 1 && pass
  teardown
}

t_garbage_threshold_is_loud() {
  setup "нечисловой порог (RENEW_OK_MAX_AGE_SEC=4h) — сломанный конфиг, а не вечно зелёная проверка"
  export STUB_CERT_DAYS=60
  conf_set "RENEW_OK_FILE=$TMP/state/certbot-renew.ok"
  conf_set "RENEW_OK_MAX_AGE_SEC=4h"
  printf '%s\n' "$(date +%s)" > "$TMP/state/certbot-renew.ok"
  run_monitor
  assert_vk_count 1 && assert_vk_contains "RENEW_OK_MAX_AGE_SEC" && assert_rc 1 && pass
  teardown
}

t_probe_is_rate_limited() {
  setup "пробная отправка при мёртвом канале — не чаще раза в 30 минут, а не каждый прогон"
  export STUB_CERT_DAYS=60
  export STUB_HTTP_todo_keepware_ru=500
  export STUB_VK=reject
  run_monitor
  unset STUB_HTTP_todo_keepware_ru
  run_monitor
  run_monitor
  assert_vk_count 1 || { teardown; return; }
  # Последняя попытка час назад — проба снова разрешена.
  local now
  now=$(date +%s)
  printf '%s 1 %s\n' "$(( now - 7200 ))" "$(( now - 3600 ))" > "$TMP/state/channel.down"
  export STUB_VK=ok
  run_monitor
  assert_vk_count 2 && pass
  teardown
}

t_probe_when_channel_state_fail_without_down_file() {
  setup "канал помечен fail, а файла пропуска нет — проба всё равно идёт, channel=fail не залипает"
  export STUB_CERT_DAYS=60
  printf 'fail' > "$TMP/state/channel.state"
  run_monitor
  assert_vk_count 1 && assert_file_contains "$TMP/state/beacon.self" "channel=ok" && pass
  teardown
}

t_gap_line_survives_truncation() {
  setup "длинное сообщение обрезается, а строка о пропуске канала остаётся сразу под заголовком"
  export STUB_CERT_DAYS=none
  : > "$TARGETS"
  local i now
  # Целей столько и с такими именами, чтобы сообщение заведомо перевалило порог
  # обрезки и в символах, и в байтах: иначе сценарий не проверяет ничего.
  for i in $(seq 1 45); do printf 'very-long-host-name-%s.keepware.ru|tls||\n' "$i" >> "$TARGETS"; done
  now=$(date +%s)
  printf '%s 3 %s\n' "$(( now - 7200 ))" "$(( now - 3600 ))" > "$TMP/state/channel.down"
  run_monitor
  vk_last | grep -qF "обрезано" \
    || fail "сообщение не обрезалось, сценарий ничего не проверяет — длина $(vk_last | wc -c | tr -d ' ') байт"
  vk_last | sed -n '2p' | grep -qF "Канал алертов не доставлял" \
    || fail "строка о пропуске канала не вторая — обрезка её съест — <<$(vk_last | head -3)>>"
  pass
  teardown
}

t_dead_peer_push_is_tried_at_most_twice() {
  setup "мёртвый пир: доставка маячка — попытка и повтор, без третьей ssh-попытки в конце прогона"
  export STUB_CERT_DAYS=60
  conf_set "PEER_TRANSPORT=ssh"
  conf_set "PEER_HOST=198.51.100.7"
  conf_set "PEER_SSH_KEY=$TMP/fake.key"
  export STUB_PEER=unreachable
  run_monitor
  local n
  n=$(grep -c 'cat >' "$TMP/ssh.argv" 2>/dev/null) || n=0
  [ "$n" -le 2 ] || fail "доставка маячка пробовалась $n раз"
  pass
  teardown
}

t_unsent_alert_is_not_duplicated_while_failure_lasts() {
  setup "отказ длится, первая отправка не прошла — повтор уходит без дубля строки"
  export STUB_CERT_DAYS=60
  export STUB_HTTP_todo_keepware_ru=500
  export STUB_VK=reject
  run_monitor
  export STUB_VK=ok
  run_monitor
  assert_vk_count 2 && assert_fail_lines 2 && pass
  teardown
}

# ========================= имя в сертификате ==============================

t_cert_for_wrong_name_alerts() {
  setup "в эфире серт не на это имя (nginx отдал чужой vhost) — тревога, хотя срок живой"
  export STUB_CERT_DAYS=60
  export STUB_CERT_NAME_keepware_ru=mismatch
  run_monitor
  assert_vk_count 1 && assert_vk_contains "TLS keepware.ru" && assert_vk_contains "не на это имя" && assert_rc 1 && pass
  teardown
}

t_empty_role_is_loud() {
  setup "роль в конфиге не задана — сломанный конфиг, а не зелёный прогон с выключенными проверками роли"
  export STUB_CERT_DAYS=60
  conf_set "ROLE="
  run_monitor
  assert_vk_count 1 && assert_vk_contains "ROLE" && assert_rc 1 && pass
  teardown
}

t_misspelled_role_is_loud() {
  setup "опечатка в роли (Peer) — проверки прода не включились бы молча"
  export STUB_CERT_DAYS=60
  conf_set "ROLE=Peer"
  run_monitor
  assert_vk_count 1 && assert_vk_contains "ROLE" && assert_rc 1 && pass
  teardown
}

t_leading_zero_threshold_is_normalized() {
  setup "порог с ведущим нулём (080) читается как 80, а не восьмеричной записью: урезание по бюджету работает"
  export STUB_CERT_DAYS=60
  conf_set "PEER_TRANSPORT=ssh"
  conf_set "PEER_HOST=198.51.100.7"
  conf_set "PEER_SSH_KEY=$TMP/fake.key"
  conf_set "PEER_SSH_TIMEOUT=100"
  conf_set "RUN_BUDGET_SEC=080"
  conf_set "SEND_RESERVE_SEC=40"
  export STUB_PEER_BEACON="ts=$(date +%s) host=peer channel=ok fails=0"
  run_monitor
  assert_vk_lacks "не число" || { teardown; return; }
  local line dur
  line="$(grep -m1 ' ssh ' "$TMP/timeout.argv" 2>/dev/null)"
  if [ -z "$line" ]; then
    fail "ssh не шёл через timeout — <<$(cat "$TMP/timeout.argv" 2>/dev/null)>>"
    teardown
    return
  fi
  dur="$(printf '%s' "$line" | awk '{print $2}')"
  case "$dur" in
    ''|*[!0-9]*) fail "не разобрал длительность таймаута из «${line}»" ;;
    *) [ "$dur" -le 40 ] || fail "таймаут ssh ${dur} с: бюджет 080 не прочитан как 80" ;;
  esac
  pass
  teardown
}

t_hour_with_leading_zero_is_not_broken_config() {
  setup "час пульса с ведущим нулём (06) — рабочее значение, а не сломанный конфиг"
  export STUB_CERT_DAYS=60
  conf_set "HEARTBEAT_HOUR=06"
  run_monitor
  assert_vk_lacks "не число" && assert_rc 0 && pass
  teardown
}

t_heartbeat_sent_when_budget_exhausted_and_nothing_was_sent() {
  setup "бюджет исчерпан, но отправок в прогоне не было (длящийся отказ) — пульс всё равно уходит"
  export STUB_CERT_DAYS=60
  conf_set "HEARTBEAT_HOUR=0"
  conf_set "RUN_BUDGET_SEC=1"
  conf_set "SEND_RESERVE_SEC=5"
  run_monitor
  # Второй прогон: те же отказы уже известны, алерт не уходит — пульс остаётся
  # единственным повторяющимся сигналом.
  run_monitor
  vk_last | grep -qF "пульс"     || fail "пульс не ушёл, хотя других отправок в прогоне не было — <<$(vk_last | head -3)>>"
  pass
  teardown
}

t_probe_last_attempt_in_future_is_not_trusted() {
  setup "время последней попытки из будущего (часы шагнули назад) не откладывает пробную отправку"
  export STUB_CERT_DAYS=60
  local now
  now=$(date +%s)
  printf '%s 1 %s\n' "$(( now - 7200 ))" "$(( now + 7200 ))" > "$TMP/state/channel.down"
  run_monitor
  assert_vk_count 1 && assert_file_contains "$TMP/state/beacon.self" "channel=ok" && pass
  teardown
}

t_heartbeat_skipped_when_budget_exhausted() {
  setup "бюджет исчерпан, а сигнал уже ушёл — пульс не отправляем и дату не пишем: он уйдёт следующим прогоном"
  export STUB_CERT_DAYS=60
  conf_set "HEARTBEAT_HOUR=0"
  # Резерв больше бюджета: остаток отрицателен с первой секунды, и сценарий не
  # зависит от скорости машины.
  conf_set "RUN_BUDGET_SEC=1"
  conf_set "SEND_RESERVE_SEC=5"
  run_monitor
  assert_vk_lacks "пульс" || { teardown; return; }
  [ -f "$TMP/state/heartbeat.date" ] && fail "дата пульса записана, хотя пульс не отправлялся"
  pass
  teardown
}

t_heartbeat_is_rate_limited_when_channel_dead() {
  setup "канал мёртв: пульс повторяется не чаще раза в 30 минут, а не каждые 5"
  export STUB_CERT_DAYS=60
  conf_set "HEARTBEAT_HOUR=0"
  export STUB_VK=reject
  run_monitor
  run_monitor
  assert_vk_count 1 || { teardown; return; }
  # Последняя попытка час назад — пульс снова разрешён.
  back_date_channel_down
  run_monitor
  assert_vk_count 2 && pass
  teardown
}

t_peer_role_renew_stamp_path_comes_from_state_dir() {
  setup "роль peer: отметка продления ищется в STATE_DIR — свежая отметка там гасит тревогу"
  export STUB_CERT_DAYS=60
  conf_set "ROLE=peer"
  export STUB_LIVE_DOMAINS="todo.keepware.ru"
  printf '%s\n' "$(date +%s)" > "$TMP/state/certbot-renew.ok"
  run_monitor
  assert_vk_count 0 && assert_rc 0 && pass
  teardown
}

t_tiny_send_reserve_is_loud() {
  setup "боевой бюджет с нулевым резервом на отправку — сломанный конфиг: прогон не успел бы отправить алерт"
  export STUB_CERT_DAYS=60
  conf_set "PEER_TRANSPORT=ssh"
  conf_set "PEER_HOST=198.51.100.7"
  conf_set "PEER_SSH_KEY=$TMP/fake.key"
  conf_set "PEER_SSH_TIMEOUT=250"
  conf_set "RUN_BUDGET_SEC=260"
  conf_set "SEND_RESERVE_SEC=0"
  export STUB_PEER_BEACON="ts=$(date +%s) host=peer channel=ok fails=0"
  run_monitor
  assert_vk_count 1 && assert_vk_contains "взято 40" && assert_rc 1 || { teardown; return; }
  # Сообщение без подмены значения ничего не стоит: проверяем, что резерв реально
  # вычтен из сетевого окна, а не только упомянут в тексте.
  assert_ssh_timeout_at_most 220 "резерв 0 не подменён на 40: сетевое окно осталось прежним"
  pass
  teardown
}

t_reserve_eats_whole_budget_is_loud() {
  setup "резерв съел бюджет (260/255) — на проверки не осталось времени, и все они рапортовали бы «не выполнялось»"
  export STUB_CERT_DAYS=60
  conf_set "RUN_BUDGET_SEC=260"
  conf_set "SEND_RESERVE_SEC=255"
  run_monitor
  # Главное здесь не текст, а то, что проверки ВЫПОЛНИЛИСЬ: без подмены значений
  # budget_for обнуляет каждую операцию, и опрашивалка тихо слепнет под видом работы.
  assert_vk_count 1 && assert_vk_contains "не оставляет времени" \
    && assert_vk_lacks "бюджет прогона исчерпан" && assert_rc 1 && pass
  teardown
}

t_run_budget_over_cron_wrapper_is_loud() {
  setup "бюджет больше cron-обёртки (600 с при timeout 280) — прогон убили бы в середине сети, без алерта и маячка"
  export STUB_CERT_DAYS=60
  conf_set "PEER_TRANSPORT=ssh"
  conf_set "PEER_HOST=198.51.100.7"
  conf_set "PEER_SSH_KEY=$TMP/fake.key"
  conf_set "PEER_SSH_TIMEOUT=500"
  conf_set "RUN_BUDGET_SEC=600"
  conf_set "SEND_RESERVE_SEC=40"
  export STUB_PEER_BEACON="ts=$(date +%s) host=peer channel=ok fails=0"
  run_monitor
  assert_vk_count 1 && assert_vk_contains "cron-обёртку" && assert_rc 1 || { teardown; return; }
  assert_ssh_timeout_at_most 220 "раздутый бюджет не подменён: клампы перестали урезать сетевые операции"
  pass
  teardown
}

t_cron_timeout_from_config_is_ignored() {
  setup "потолок обёртки из конфига не слушается: иначе конфиг отключал бы сторожа, который его же и стережёт"
  export STUB_CERT_DAYS=60
  conf_set "CRON_TIMEOUT_SEC=600"
  conf_set "RUN_BUDGET_SEC=560"
  conf_set "SEND_RESERVE_SEC=40"
  run_monitor
  # Потолок приходит строкой cron, а не конфигом: 560 − 40 + 30 = 550 не умещается
  # в реальные 280, и это обязано быть громким, сколько бы ни писали в конфиге.
  assert_vk_count 1 && assert_vk_contains "cron-обёртку" && assert_rc 1 && pass
  teardown
}

t_fallback_fits_a_smaller_cron_wrapper() {
  setup "обёртка 150 с: запасные значения выводятся из потолка, а не прибиты к 260/40"
  export STUB_CERT_DAYS=60
  conf_set "PEER_TRANSPORT=ssh"
  conf_set "PEER_HOST=198.51.100.7"
  conf_set "PEER_SSH_KEY=$TMP/fake.key"
  conf_set "PEER_SSH_TIMEOUT=500"
  conf_set "RUN_BUDGET_SEC=600"
  conf_set "SEND_RESERVE_SEC=40"
  export STUB_PEER_BEACON="ts=$(date +%s) host=peer channel=ok fails=0"
  # Потолок приходит из окружения — так его передаёт строка cron, которую пишет установщик.
  export CRON_TIMEOUT_SEC=150
  run_monitor
  unset CRON_TIMEOUT_SEC
  # Точные числа, а не порог: прибитые 260/40 под обёрткой 150 с нарушают инвариант
  # ровно так же, как исходный конфиг, — «починка» обязана считаться от потолка.
  assert_vk_count 1 && assert_vk_contains "взято 159/40" && assert_rc 1 || { teardown; return; }
  assert_ssh_timeout_at_most 130 "запасной бюджет не выведен из обёртки 150 с: окно осталось от 260/40"
  pass
  teardown
}

# ------------------------------------------------------------------ run ---

echo "Тесты портфельной опрашивалки"
# PM_TEST_FILTER=подстрока — прогнать только совпавшие (набор под Windows идёт минутами).
for t in $(declare -F | awk '{print $3}' | grep '^t_' | grep -- "${PM_TEST_FILTER:-}"); do "$t"; done

echo
printf 'Пройдено: %d, провалено: %d\n' "$PASSED" "$FAILED"

# Сценарий, потерянный опечаткой в имени, иначе исчезает бесшумно, а набор
# рапортует «провалено: 0» — тот же тихий отказ, что и в самом мониторинге.
if [ -z "${PM_TEST_FILTER:-}" ]; then
  DECLARED=$(declare -F | awk '{print $3}' | grep -c '^t_')
  RAN=$((PASSED + FAILED))
  if [ "$RAN" -lt "$DECLARED" ]; then
    printf 'ОШИБКА: объявлено сценариев %d, отчиталось %d — какой-то не запустился\n' "$DECLARED" "$RAN"
    exit 1
  fi
fi

[ "$FAILED" -eq 0 ] || exit 1
