#!/usr/bin/env bash
# Тесты monitoring/certbot-renew.sh. Docker не трогается: подменён заглушкой из
# stubs-certbot/, которая изображает контейнер certbot каталогом во временной папке
# и исполняет test / find / sh над настоящими файлами — времена файлов тут и есть
# предмет проверки.
#
# Запуск: bash monitoring/tests/certbot-renew.test.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/../certbot-renew.sh"
STUBS="$HERE/stubs-certbot"

# Набор безопасен, только пока заглушка перехватывает вызов. Заглушка без бита
# исполнения молча пропускается при поиске по PATH, и тест дёрнул бы настоящий
# docker: на проде — certbot renew и reload общего nginx.
if [ "$( PATH="$STUBS:$PATH"; command -v docker )" != "$STUBS/docker" ]; then
  echo "ОШИБКА: заглушка docker не перехватывает вызов (нет бита исполнения?) — набор не запускается" >&2
  exit 1
fi

MARKER=/etc/letsencrypt/.nginx-reload-needed
STAMP=/etc/letsencrypt/.nginx-reload-stamp
HOOK=/etc/letsencrypt/renewal-hooks/deploy/nginx-reload-marker.sh

PASSED=0
FAILED=0
SKIPPED=0
CURRENT=""
FAILED_IN_CASE=0

setup() {
  CURRENT="$1"
  FAILED_IN_CASE=0
  TMP="$(mktemp -d)"
  export STUB_STATE="$TMP"
  export RENEW_OK_FILE="$TMP/certbot-renew.ok"
  mkdir -p "$TMP/fs/etc/letsencrypt"
  : > "$TMP/docker.argv"
  unset STUB_RUNNING STUB_RENEW_RC STUB_RENEW_WRITES STUB_RELOAD_RC STUB_RENEW_DURING_RELOAD \
        STUB_TOUCH_FAIL STUB_MV_FAIL 2>/dev/null || true
}

teardown() { [ -n "${TMP:-}" ] && rm -rf "$TMP"; }

# fs_touch <путь в контейнере> [когда] — файл с заданным временем изменения.
# Без смещения время округляется до целой секунды — так же, как это делает заглушка
# docker. В контейнере busybox сравнивает времена файлов секундами; округлив только
# одну сторону, мы поставили бы сравнение «маркер новее метки» в зависимость от того,
# попали ли касания в одну секунду. В Git Bash форки медленные и попадают в разные,
# на Linux — в одну, и сценарий падал бы почти в каждом прогоне.
fs_touch() {
  mkdir -p "$(dirname "$TMP/fs$1")"
  : > "$TMP/fs$1"
  if [ -n "${2:-}" ]; then
    touch -d "$2" "$TMP/fs$1"
  else
    touch -d "@$(date +%s)" "$TMP/fs$1"
  fi
  return 0
}
fs_has() { [ -f "$TMP/fs$1" ]; }

run_renew() {
  PATH="$STUBS:$PATH" bash "$SCRIPT" > "$TMP/out" 2>&1
  RC=$?
}

reloads() { local n; n=$(grep -c '^exec nginx-proxy nginx -s reload' "$TMP/docker.argv") || n=0; echo "$n"; }

pass() { [ "$FAILED_IN_CASE" = "0" ] && { PASSED=$((PASSED + 1)); printf '  ok   %s\n' "$CURRENT"; }; }
fail() { FAILED=$((FAILED + 1)); FAILED_IN_CASE=1; printf '  FAIL %s\n       %s\n' "$CURRENT" "$1"; }

assert_rc() { [ "$RC" = "$1" ] || { fail "код возврата: ожидали $1, получили $RC — <<$(cat "$TMP/out")>>"; return 1; }; return 0; }

assert_reloads() {
  [ "$(reloads)" = "$1" ] || { fail "reload вызван $(reloads) раз, ждали $1 — <<$(cat "$TMP/docker.argv")>>"; return 1; }
  return 0
}

assert_out_contains() {
  grep -qF -- "$1" "$TMP/out" || { fail "в выводе нет «$1» — <<$(cat "$TMP/out")>>"; return 1; }
  return 0
}

assert_ok_stamp() {
  if [ "$1" = "yes" ]; then
    [ -s "$RENEW_OK_FILE" ] || { fail "отметка успешного прогона не записана"; return 1; }
  else
    [ -e "$RENEW_OK_FILE" ] && { fail "отметка успешного прогона записана при неудачном прогоне"; return 1; }
  fi
  return 0
}

# ------------------------------------------------------------- сценарии ----

t_stopped_container_is_loud() {
  setup "остановленный контейнер certbot — громкий отказ, а не тихое «нечего делать»"
  export STUB_RUNNING=false
  run_renew
  assert_rc 1 && assert_reloads 0 && assert_out_contains "не запущен" && assert_ok_stamp no && pass
  teardown
}

t_absent_container_is_loud() {
  setup "контейнера certbot нет вовсе — отказ"
  export STUB_RUNNING=absent
  run_renew
  assert_rc 1 && assert_reloads 0 && assert_ok_stamp no && pass
  teardown
}

t_hook_is_a_file_not_a_cli_flag() {
  setup "хук продления кладётся файлом в renewal-hooks/deploy, флаг --deploy-hook не передаётся"
  fs_touch "$STAMP"
  run_renew
  assert_rc 0 || { teardown; return; }
  grep -q -- "--deploy-hook" "$TMP/docker.argv" \
    && fail "certbot получил --deploy-hook: certbot сохранит его в renewal-конфиги всех сертов — <<$(cat "$TMP/docker.argv")>>"
  fs_has "$HOOK" || fail "файл хука $HOOK не положен"
  grep -qF "touch ${MARKER}" "$TMP/fs$HOOK" 2>/dev/null || fail "хук не ставит маркер — <<$(cat "$TMP/fs$HOOK" 2>/dev/null)>>"
  pass
  teardown
}

t_hook_is_not_rewritten_every_run() {
  setup "хук на месте — второй прогон его не переписывает"
  fs_touch "$STAMP"
  run_renew
  run_renew
  local n
  n=$(grep -c 'sh -c mkdir' "$TMP/docker.argv") || n=0
  [ "$n" = "1" ] || fail "хук записан $n раз, ждали один — <<$(cat "$TMP/docker.argv")>>"
  pass
  teardown
}

t_renewal_in_this_run_reloads_via_hook() {
  setup "продление этим прогоном: хук ставит маркер, nginx перечитан, маркер снят"
  fs_touch "$STAMP" "-2 hours"
  export STUB_RENEW_WRITES=1
  run_renew
  assert_rc 0 && assert_reloads 1 && assert_out_contains "deploy-hook" || { teardown; return; }
  fs_has "$MARKER" && fail "маркер не снят после успешного reload"
  pass
  teardown
}

t_marker_triggers_reload() {
  setup "маркер стоит — nginx перезагружается, маркер снят, метка обновлена, отметка успеха записана"
  fs_touch "$STAMP" "-2 hours"
  fs_touch "$MARKER"
  run_renew
  assert_rc 0 && assert_reloads 1 && assert_ok_stamp yes || { teardown; return; }
  fs_has "$MARKER" && fail "маркер не снят после успешного reload"
  [ -n "$(find "$TMP/fs$STAMP" -newermt '-10 minutes' 2>/dev/null)" ] || fail "метка последнего reload не обновлена"
  pass
  teardown
}

t_first_run_reloads() {
  setup "первый прогон (метки нет) — перезагружаем, состояние nginx неизвестно"
  run_renew
  assert_rc 0 && assert_reloads 1 || { teardown; return; }
  fs_has "$STAMP" || fail "метка последнего reload не поставлена"
  pass
  teardown
}

t_nothing_to_do_is_silent() {
  setup "маркера нет, в архиве ничего новее метки — reload не трогаем"
  fs_touch "/etc/letsencrypt/archive/todo.keepware.ru/cert1.pem" "-3 days"
  fs_touch "$STAMP" "-1 hour"
  run_renew
  assert_rc 0 && assert_reloads 0 && assert_ok_stamp yes && pass
  teardown
}

t_container_renewal_reloads_once() {
  setup "продление циклом контейнера (маркера нет, серт в архиве новее метки) — один reload, дальше тишина"
  fs_touch "$STAMP" "-2 hours"
  fs_touch "/etc/letsencrypt/archive/todo.keepware.ru/cert2.pem" "-30 minutes"
  run_renew
  run_renew
  assert_rc 0 && assert_reloads 1 && pass
  teardown
}

t_marker_set_during_reload_survives() {
  setup "продление пришлось на время reload — его маркер не стирается, следующий прогон перечитает"
  fs_touch "$STAMP" "-2 hours"
  fs_touch "$MARKER" "-1 minute"
  export STUB_RENEW_DURING_RELOAD=1
  run_renew
  assert_rc 0 && assert_reloads 1 || { teardown; return; }
  fs_has "$MARKER" || fail "маркер продления, пришедшего во время reload, стёрт — это продление никто не перечитает"
  pass
  teardown
}

t_failed_reload_keeps_marker() {
  setup "reload упал — маркер ОСТАЁТСЯ, отметки успеха нет, повторим в следующий прогон"
  fs_touch "$STAMP" "-2 hours"
  fs_touch "$MARKER"
  export STUB_RELOAD_RC=1
  run_renew
  assert_rc 1 && assert_ok_stamp no || { teardown; return; }
  fs_has "$MARKER" || fail "маркер снят при неудачном reload — продление потеряно молча"
  pass
  teardown
}

t_renew_failure_does_not_block_reload() {
  setup "certbot вернул не 0 (не продлился чужой серт), маркер стоит — reload делаем, отметка работоспособности пишется"
  fs_touch "$STAMP" "-2 hours"
  fs_touch "$MARKER"
  export STUB_RENEW_RC=1
  run_renew
  assert_reloads 1 && assert_rc 1 && assert_ok_stamp yes && pass
  teardown
}

t_stamp_update_failure_withholds_ok_stamp() {
  setup "метку reload поставить не удалось — nginx перечитывался бы каждый час, отметку успеха не пишем"
  fs_touch "$STAMP" "-2 hours"
  fs_touch "$MARKER"
  export STUB_TOUCH_FAIL=1
  run_renew
  unset STUB_TOUCH_FAIL
  assert_reloads 1 && assert_rc 1 && assert_ok_stamp no && pass
  teardown
}

# Git Bash считает исполняемым любой файл с shebang, поэтому сценарий про снятый
# бит там не может упасть — он пропускается с отметкой. На Linux (CI, хосты) он
# настоящий.
exec_bit_is_honored() {
  local p="$TMP/exec-probe.sh"
  printf '#!/bin/sh\n' > "$p"
  chmod 644 "$p"
  [ ! -x "$p" ]
}

# Пропуск — не победа: он считается отдельно и виден в итоге. На Linux бит
# исполнения обязан различаться, поэтому там пропуск означал бы, что сценарий тихо
# исчез из набора, — это отказ.
skip_case() {
  if [ "$(uname -s)" = "Linux" ]; then
    fail "сценарий пропущен на Linux ($1) — здесь он обязан выполняться"
    return
  fi
  printf '  skip %s (%s)\n' "$CURRENT" "$1"
  SKIPPED=$((SKIPPED + 1))
}

t_hook_without_exec_bit_is_repaired() {
  setup "хук с верным текстом, но без бита исполнения — переписываем: certbot пропускает неисполняемые хуки молча"
  fs_touch "$STAMP"
  mkdir -p "$(dirname "$TMP/fs$HOOK")"
  printf '#!/bin/sh\n# Ставится monitoring/certbot-renew.sh: маркер говорит хосту перезагрузить nginx.\ntouch %s\n' "$MARKER" > "$TMP/fs$HOOK"
  chmod 644 "$TMP/fs$HOOK"
  if ! exec_bit_is_honored; then
    skip_case "окружение не различает бит исполнения"
    teardown
    return
  fi
  run_renew
  assert_rc 0 || { teardown; return; }
  grep -q 'sh -c mkdir' "$TMP/docker.argv" \
    || fail "хук без бита исполнения не переписан — certbot будет его пропускать <<$(cat "$TMP/docker.argv")>>"
  pass
  teardown
}

t_stamp_move_failure_withholds_ok_stamp() {
  setup "метку поставили, но перенести на место не удалось — отметку работоспособности не пишем"
  fs_touch "$STAMP" "-2 hours"
  fs_touch "$MARKER"
  export STUB_MV_FAIL=1
  run_renew
  unset STUB_MV_FAIL
  assert_reloads 1 && assert_rc 1 && assert_ok_stamp no || { teardown; return; }
  fs_has "$MARKER" || fail "маркер снят, хотя метка не обновилась — это продление никто не перечитает"
  pass
  teardown
}

# ------------------------------------------------------------------ run ---

echo "Тесты certbot-renew"
for t in $(declare -F | awk '{print $3}' | grep '^t_'); do "$t"; done

echo
printf 'Пройдено: %d, провалено: %d, пропущено: %d\n' "$PASSED" "$FAILED" "$SKIPPED"

# Сценарий, выпавший молча (утверждение без fail в цепочке, опечатка в имени),
# иначе исчезает бесшумно, а набор рапортует «провалено: 0».
DECLARED=$(declare -F | awk '{print $3}' | grep -c '^t_')
if [ $((PASSED + FAILED + SKIPPED)) -lt "$DECLARED" ]; then
  printf 'ОШИБКА: объявлено сценариев %d, отчиталось %d — какой-то не запустился\n' "$DECLARED" "$((PASSED + FAILED + SKIPPED))"
  exit 1
fi

[ "$FAILED" -eq 0 ] || exit 1
