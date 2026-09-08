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
EOF

  unset STUB_HTTP STUB_HTTP_todo_keepware_ru STUB_HTTP_keepware_ru \
        STUB_CERT_DAYS STUB_CERT_DAYS_todo_keepware_ru STUB_CERT_DAYS_keepware_ru \
        STUB_WIRE_AGE_DAYS STUB_DISK_AGE_DAYS STUB_DISK \
        STUB_PEER STUB_PEER_BEACON STUB_VK 2>/dev/null || true
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
  printf 'echo old\n' > "$TMP/installed/server-monitor.sh"
  printf 'echo new\n' > "$TMP/repo/server-monitor.sh"
  conf_set "COPY_DRIFT_CHECK=$TMP/installed:$TMP/repo"
  run_monitor
  assert_vk_count 1 && assert_vk_contains "server-monitor.sh" && assert_rc 1 && pass
  teardown
}

t_copy_in_sync_is_silent() {
  setup "копия совпадает с репозиторием — тишина"
  export STUB_CERT_DAYS=60
  mkdir -p "$TMP/installed" "$TMP/repo"
  printf 'echo same\n' > "$TMP/installed/server-monitor.sh"
  printf 'echo same\n' > "$TMP/repo/server-monitor.sh"
  conf_set "COPY_DRIFT_CHECK=$TMP/installed:$TMP/repo"
  run_monitor
  assert_vk_count 0 && assert_rc 0 && pass
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
