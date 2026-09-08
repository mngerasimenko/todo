#!/usr/bin/env bash
# Тесты monitoring/certbot-renew.sh. Docker не трогается: подменён заглушкой
# из stubs-certbot/ с маленькой файловой системой во временном каталоге.
#
# Запуск: bash monitoring/tests/certbot-renew.test.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/../certbot-renew.sh"
STUBS="$HERE/stubs-certbot"

MARKER=/etc/letsencrypt/.nginx-reload-needed
STAMP=/etc/letsencrypt/.nginx-reload-stamp

PASSED=0
FAILED=0
CURRENT=""
FAILED_IN_CASE=0

setup() {
  CURRENT="$1"
  FAILED_IN_CASE=0
  TMP="$(mktemp -d)"
  export STUB_STATE="$TMP"
  mkdir -p "$TMP/fs/etc/letsencrypt"
  : > "$TMP/docker.argv"
  unset STUB_RUNNING STUB_RENEW_RC STUB_RELOAD_RC STUB_ARCHIVE_NEWER STUB_CLEANUP_RC 2>/dev/null || true
}

teardown() { [ -n "${TMP:-}" ] && rm -rf "$TMP"; }

fs_touch() { mkdir -p "$(dirname "$TMP/fs$1")"; : > "$TMP/fs$1"; }
fs_has()   { [ -f "$TMP/fs$1" ]; }

run_renew() {
  PATH="$STUBS:$PATH" bash "$SCRIPT" > "$TMP/out" 2>&1
  RC=$?
}

pass() { [ "$FAILED_IN_CASE" = "0" ] && { PASSED=$((PASSED + 1)); printf '  ok   %s\n' "$CURRENT"; }; }
fail() { FAILED=$((FAILED + 1)); FAILED_IN_CASE=1; printf '  FAIL %s\n       %s\n' "$CURRENT" "$1"; }

assert_rc() { [ "$RC" = "$1" ] || { fail "код возврата: ожидали $1, получили $RC — <<$(cat "$TMP/out")>>"; return 1; }; return 0; }

assert_reloaded() {
  grep -q '^exec nginx-proxy nginx -s reload' "$TMP/docker.argv" \
    || { fail "reload не вызывался — <<$(cat "$TMP/docker.argv")>>"; return 1; }
  return 0
}

assert_not_reloaded() {
  grep -q '^exec nginx-proxy nginx -s reload' "$TMP/docker.argv" \
    && { fail "reload вызывался, а не должен был — <<$(cat "$TMP/docker.argv")>>"; return 1; }
  return 0
}

# ------------------------------------------------------------- сценарии ----

t_stopped_container_is_loud() {
  setup "остановленный контейнер certbot — громкий отказ, а не тихое «нечего делать»"
  export STUB_RUNNING=false
  run_renew
  assert_rc 1 && assert_not_reloaded && grep -q "не запущен" "$TMP/out" && pass
  teardown
}

t_absent_container_is_loud() {
  setup "контейнера certbot нет вовсе — отказ"
  export STUB_RUNNING=absent
  run_renew
  assert_rc 1 && assert_not_reloaded && pass
  teardown
}

t_deploy_hook_is_passed_to_certbot() {
  setup "certbot зовётся именно с --deploy-hook, ставящим маркер"
  fs_touch "$STAMP"
  run_renew
  grep -q -- "--deploy-hook touch ${MARKER}" "$TMP/docker.argv" \
    || fail "в вызове certbot нет --deploy-hook — <<$(cat "$TMP/docker.argv")>>"
  pass
  teardown
}

t_marker_triggers_reload() {
  setup "маркер от deploy-hook стоит — nginx перезагружается, маркер снят, метка поставлена"
  fs_touch "$STAMP"
  fs_touch "$MARKER"
  run_renew
  assert_rc 0 && assert_reloaded || { teardown; return; }
  fs_has "$MARKER" && fail "маркер не снят после успешного reload"
  fs_has "$STAMP" || fail "метка последнего reload не поставлена"
  pass
  teardown
}

t_first_run_reloads() {
  setup "первый прогон (метки нет) — перезагружаем, состояние nginx неизвестно"
  run_renew
  assert_rc 0 && assert_reloaded && pass
  teardown
}

t_nothing_to_do_is_silent() {
  setup "маркера нет, метка есть, в архиве ничего нового — reload не трогаем"
  fs_touch "$STAMP"
  run_renew
  assert_rc 0 && assert_not_reloaded && pass
  teardown
}

t_archive_newer_reloads_without_hook() {
  setup "хук не сработал, но в архиве серт новее метки — страховка перезагружает"
  fs_touch "$STAMP"
  export STUB_ARCHIVE_NEWER=1
  run_renew
  assert_rc 0 && assert_reloaded && pass
  teardown
}

t_failed_reload_keeps_marker() {
  setup "reload упал — маркер ОСТАЁТСЯ, повторим в следующий прогон"
  fs_touch "$STAMP"
  fs_touch "$MARKER"
  export STUB_RELOAD_RC=1
  run_renew
  assert_rc 1 || { teardown; return; }
  fs_has "$MARKER" || fail "маркер снят при неудачном reload — продление потеряно молча"
  pass
  teardown
}

t_renew_failure_does_not_block_reload() {
  setup "certbot вернул не 0, но маркер стоит — reload всё равно делаем (не вешаем на &&)"
  fs_touch "$STAMP"
  fs_touch "$MARKER"
  export STUB_RENEW_RC=1
  run_renew
  assert_reloaded && assert_rc 1 && pass
  teardown
}

# ------------------------------------------------------------------ run ---

echo "Тесты certbot-renew"
for t in $(declare -F | awk '{print $3}' | grep '^t_'); do "$t"; done

echo
printf 'Пройдено: %d, провалено: %d\n' "$PASSED" "$FAILED"
[ "$FAILED" -eq 0 ] || exit 1
