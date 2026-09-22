#!/usr/bin/env bash
# Тесты отправки в VK у трёх старых скриптов мониторинга: server-monitor.sh,
# stats-report.sh и vk-bot.sh. Сеть, docker, free/df и python3 подменены
# заглушками из stubs-vksend/ — ни одного настоящего вызова наружу.
#
# Запуск: bash monitoring/tests/vk-send.test.sh
# Требуется: bash 4+. jq и python3 НЕ требуются намеренно — набор гоняется и на
# хосте, и в Git Bash под Windows.
#
# Что здесь проверяется по сути:
#   1) секрет (токен группы, сессионный ключ Long Poll) не попадает в argv curl,
#      но при этом реально уезжает конфигом на stdin — иначе «чисто в argv»
#      прошло бы и на скрипте, который вообще перестал отправлять;
#   2) отправитель смотрит, что ответил VK: отвергнутая отправка не считается
#      доставленной и не пропадает молча.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$HERE/.."
STUBS="$HERE/stubs-vksend"

# Набор безопасен, только пока заглушки перехватывают вызовы. Заглушка без бита
# исполнения молча пропускается при поиске по PATH — и тест пошёл бы в сеть, к
# настоящему VK, а на хосте ещё и к docker.
for s in curl docker free df python3; do
  if [ "$( PATH="$STUBS:$PATH"; command -v "$s" )" != "$STUBS/$s" ]; then
    echo "ОШИБКА: заглушка $s не перехватывает вызов (нет бита исполнения?) — набор не запускается" >&2
    exit 1
  fi
done

PASSED=0
FAILED=0
CURRENT=""
FAILED_IN_CASE=0

TOKEN="vk-test-token-51a9"
PEER="147952703"

setup() {
  CURRENT="$1"
  FAILED_IN_CASE=0
  TMP="$(mktemp -d)"
  export STUB_STATE="$TMP"
  export STUB_VK_OUT="$TMP/vk.out"
  : > "$STUB_VK_OUT"
  : > "$TMP/curl.argv"
  : > "$TMP/curl.cfg"

  # Скрипты сорсят конфиг рядом с собой и ищут рядом же общую отправку —
  # поэтому гоняем их из копии, а не из рабочей копии репозитория: иначе набор
  # оставлял бы monitor.conf в дереве.
  MON="$TMP/mon"
  mkdir -p "$MON"
  cp "$SRC/server-monitor.sh" "$SRC/stats-report.sh" "$SRC/vk-bot.sh" "$MON/"
  [ -f "$SRC/vk-send.sh" ] && cp "$SRC/vk-send.sh" "$MON/"
  chmod +x "$MON"/*.sh 2>/dev/null || true

  CONF="$MON/monitor.conf"
  cat > "$CONF" <<EOF
VK_TOKEN="$TOKEN"
VK_PEER_ID="$PEER"
VK_GROUP_ID="123456"
VK_API_VERSION="5.199"
RAM_WARN=80
SWAP_WARN=50
DISK_WARN=85
JVM_HEAP_WARN=85
ALERT_COOLDOWN=1800
MONITOR_CONTAINERS="todo-app"
ALERT_STATE_FILE="$TMP/alert-state"
EOF

  unset STUB_VK STUB_LP STUB_RAM_PERCENT STUB_SWAP_PERCENT STUB_DISK_PERCENT \
        STUB_CONTAINER_STATUS STUB_API_CODE STUB_API_JSON STUB_USAGESTATS STUB_ACTUATOR STUB_PG STUB_DOCKER_RESTART 2>/dev/null || true
}

teardown() { [ -n "${TMP:-}" ] && rm -rf "$TMP"; }

conf_set() { printf '%s\n' "$1" >> "$CONF"; }

pass() { [ "$FAILED_IN_CASE" = "0" ] && { PASSED=$((PASSED + 1)); printf '  ok   %s\n' "$CURRENT"; }; }
fail() { FAILED=$((FAILED + 1)); FAILED_IN_CASE=1; printf '  FAIL %s\n       %s\n' "$CURRENT" "$1"; }

# Сторож времени обязателен: скрипт, который вместо отправки уходит в вечный
# цикл (ровно так выглядит тело vk-bot.sh), иначе не провалил бы тест, а
# подвесил весь набор — и в CI это читалось бы как «тесты идут».
run_script() {
  local script="$1"; shift
  PATH="$STUBS:$PATH" timeout 20 bash "$MON/$script" "$@" > "$TMP/run.out" 2> "$TMP/run.err"
  RC=$?
  [ "$RC" = "124" ] && fail "скрипт $script не завершился за 20 с — <<$(err_text)>>"
}

# Вызов функции скрипта без запуска его тела: так проверяется vk-bot.sh,
# у которого тело — вечный цикл опроса. Признак сорсинга — сам факт сорсинга
# (BASH_SOURCE против $0), никакой переменной окружения тут не передаётся.
run_bot_func() {
  PATH="$STUBS:$PATH" timeout 20 bash -c '
    . "$1" || exit 99
    shift
    "$@"
  ' _ "$MON/vk-bot.sh" "$@" > "$TMP/run.out" 2> "$TMP/run.err"
  RC=$?
  [ "$RC" = "124" ] && fail "vk-bot.sh не отдал управление за 20 с: тело скрипта поднялось при сорсинге"
}

# Запуск бота как программы — с коротким сторожем: тело обязано подняться, и
# единственный его признак снаружи — что он не завершается сам.
run_bot_body() {
  PATH="$STUBS:$PATH" timeout 8 bash "$MON/vk-bot.sh" > "$TMP/run.out" 2> "$TMP/run.err"
  RC=$?
}

vk_count() { local n; n=$(grep -c '^---$' "$STUB_VK_OUT" 2>/dev/null); [ -n "$n" ] || n=0; echo "$n"; }
vk_text() { cat "$STUB_VK_OUT" 2>/dev/null; }
err_text() { cat "$TMP/run.err" 2>/dev/null; }

assert_curl_was_called() {
  [ -s "$TMP/curl.argv" ] || { fail "curl вообще не вызывался — проверка вырождена"; return 1; }
  return 0
}

assert_vk_count() {
  local want="$1" got
  got=$(vk_count)
  [ "$got" = "$want" ] || { fail "отправок в VK: ожидали $want, получили $got — <<$(vk_text)>>"; return 1; }
  return 0
}

# Секрет не в командной строке — но и не потерян: он обязан быть в конфиге,
# который уехал curl'у на stdin. Без второй половины проверка проходит на
# скрипте, который просто перестал отправлять.
assert_secret_hidden() {
  local secret="$1"
  if grep -qF -- "$secret" "$TMP/curl.argv" 2>/dev/null; then
    fail "секрет найден в argv curl: $(grep -m1 -oF -- "$secret" "$TMP/curl.argv")"
    return 1
  fi
  if ! grep -qF -- "$secret" "$TMP/curl.cfg" 2>/dev/null; then
    fail "секрета нет и в конфиге curl — отправка не состоялась вовсе: <<$(cat "$TMP/curl.cfg" 2>/dev/null)>>"
    return 1
  fi
  return 0
}

assert_err_contains() {
  grep -qF -- "$1" "$TMP/run.err" 2>/dev/null || { fail "в stderr нет «$1» — <<$(err_text)>>"; return 1; }
  return 0
}

assert_err_lacks() {
  grep -qF -- "$1" "$TMP/run.err" 2>/dev/null && { fail "в stderr не должно быть «$1» — <<$(err_text)>>"; return 1; }
  return 0
}

assert_rc() {
  [ "$RC" = "$1" ] || { fail "код возврата: ожидали $1, получили $RC — <<$(err_text)>>"; return 1; }
  return 0
}

# ------------------------------------------------------- server-monitor ---

# Алерт по RAM: порог опущен, заглушка free отдаёт занятость выше него.
arm_ram_alert() { conf_set "RAM_WARN=5"; export STUB_RAM_PERCENT=50; }

t_server_monitor_secret_not_in_argv() {
  setup "server-monitor: токен уходит конфигом, а не командной строкой"
  arm_ram_alert
  run_script server-monitor.sh
  assert_curl_was_called || { teardown; return; }
  assert_vk_count 1 || { teardown; return; }
  assert_secret_hidden "$TOKEN" && pass
  teardown
}

t_server_monitor_reject_is_loud() {
  setup "server-monitor: отвергнутая VK отправка попадает в лог, а не в тишину"
  arm_ram_alert
  export STUB_VK=reject
  run_script server-monitor.sh
  assert_curl_was_called || { teardown; return; }
  assert_err_contains "VK отверг" && pass
  teardown
}

t_server_monitor_curl_failure_is_loud() {
  setup "server-monitor: упавший curl тоже слышно"
  arm_ram_alert
  export STUB_VK=curlfail
  run_script server-monitor.sh
  assert_err_contains "curl" && pass
  teardown
}

t_server_monitor_delivered_alert_keeps_cooldown() {
  setup "server-monitor: доставленный алерт держит кулдаун — повтора нет"
  arm_ram_alert
  run_script server-monitor.sh
  run_script server-monitor.sh
  assert_vk_count 1 && pass
  teardown
}

t_server_monitor_undelivered_alert_retries() {
  setup "server-monitor: недоставленный алерт повторяется, а не съедается кулдауном"
  arm_ram_alert
  export STUB_VK=reject
  run_script server-monitor.sh
  run_script server-monitor.sh
  assert_vk_count 2 && pass
  teardown
}

t_server_monitor_quiet_host_sends_nothing() {
  setup "server-monitor: без отказов ничего не шлётся"
  export STUB_RAM_PERCENT=10
  run_script server-monitor.sh
  assert_vk_count 0 && pass
  teardown
}

# --------------------------------------------------------- stats-report ---

t_stats_report_secret_not_in_argv() {
  setup "stats-report: токен уходит конфигом, а не командной строкой"
  run_script stats-report.sh
  assert_curl_was_called || { teardown; return; }
  assert_vk_count 1 || { teardown; return; }
  assert_secret_hidden "$TOKEN" && pass
  teardown
}

t_stats_report_reject_is_loud() {
  setup "stats-report: отвергнутая VK сводка попадает в лог"
  export STUB_VK=reject
  run_script stats-report.sh
  assert_curl_was_called || { teardown; return; }
  assert_err_contains "VK отверг" && pass
  teardown
}

t_stats_report_no_actuator_sends_nothing() {
  setup "stats-report: actuator промолчал — сводка не шлётся"
  export STUB_ACTUATOR=down
  run_script stats-report.sh
  assert_vk_count 0 && pass
  teardown
}

# -------------------------------------------------------------- vk-bot ---

t_vk_bot_send_secret_not_in_argv() {
  setup "vk-bot: ответ владельцу уходит конфигом, а не командной строкой"
  run_bot_func send_message "$PEER" "статус"
  assert_curl_was_called || { teardown; return; }
  assert_vk_count 1 || { teardown; return; }
  assert_secret_hidden "$TOKEN" && pass
  teardown
}

t_vk_bot_send_reject_is_loud() {
  setup "vk-bot: отвергнутый ответ владельцу попадает в journal"
  export STUB_VK=reject
  run_bot_func send_message "$PEER" "статус"
  assert_err_contains "VK отверг" && pass
  teardown
}

t_vk_bot_longpoll_token_not_in_argv() {
  setup "vk-bot: токен не светится и при получении Long Poll сервера"
  run_bot_func fetch_longpoll_server
  assert_curl_was_called || { teardown; return; }
  assert_secret_hidden "$TOKEN" && pass
  teardown
}

t_vk_bot_longpoll_key_not_in_argv() {
  setup "vk-bot: сессионный ключ Long Poll тоже не светится в ps"
  run_bot_func poll_longpoll "https://lp.vk.com/wh42" "lp-session-key" "175"
  assert_curl_was_called || { teardown; return; }
  assert_secret_hidden "lp-session-key" && pass
  teardown
}

t_vk_bot_longpoll_reject_is_loud() {
  setup "vk-bot: VK отверг выдачу Long Poll сервера — это видно"
  export STUB_LP=reject
  run_bot_func fetch_longpoll_server
  assert_err_contains "VK отверг" && pass
  teardown
}

t_vk_bot_body_starts_when_executed() {
  setup "vk-bot: запущенный как программа, бот поднимает цикл опроса, а не выходит молча"
  export STUB_LP=reject
  run_bot_body
  # 124 — сторож прервал живой цикл, это и есть «тело поднялось».
  [ "$RC" = "124" ] || fail "бот завершился сам (rc=$RC) — шов сорсинга выключил тело: <<$(cat "$TMP/run.out")>>"
  grep -qF "VK-бот запущен" "$TMP/run.out" 2>/dev/null || fail "бот не дошёл до главного цикла — <<$(cat "$TMP/run.out")>>"
  pass
  teardown
}

t_vk_bot_restart_reports_failure() {
  setup "vk-bot: не поднявшийся контейнер — это «❌», а не «✅ перезапущен»"
  export STUB_DOCKER_RESTART=fail
  run_bot_func cmd_restart "$PEER" todo-app
  grep -qF "Ошибка перезапуска" "$STUB_VK_OUT" 2>/dev/null \
    || fail "владельцу ушёл успех на неудавшемся рестарте — <<$(vk_text)>>"
  pass
  teardown
}

t_vk_bot_restart_reports_success() {
  setup "vk-bot: удачный рестарт по-прежнему отвечает «✅»"
  run_bot_func cmd_restart "$PEER" todo-app
  grep -qF "перезапущен" "$STUB_VK_OUT" 2>/dev/null || fail "нет подтверждения удачного рестарта — <<$(vk_text)>>"
  grep -qF "Ошибка перезапуска" "$STUB_VK_OUT" 2>/dev/null && fail "удачный рестарт объявлен ошибкой — <<$(vk_text)>>"
  pass
  teardown
}

# ----------------------------------------------------- общая библиотека ---

t_message_text_survives_config_transport() {
  setup "текст сообщения доезжает без потерь: кавычки, обратный слэш, @ и перевод строки"
  # Ровно те символы, которые ломают конфиг curl или его разбор. Если переход на
  # -K - испортил текст, владелец получит мусор вместо алерта — и никто не узнает.
  run_bot_func send_message "$PEER" 'строка "в кавычках" и \обратный слэш
вторая строка @файл 100% готово'
  assert_vk_count 1 || { teardown; return; }
  grep -qF 'строка "в кавычках" и \обратный слэш' "$STUB_VK_OUT" \
    || fail "первая строка текста испорчена — <<$(vk_text)>>"
  grep -qF 'вторая строка @файл 100% готово' "$STUB_VK_OUT" \
    || fail "вторая строка текста испорчена или потеряна — <<$(vk_text)>>"
  pass
  teardown
}

t_newline_in_value_cannot_add_curl_option() {
  setup "перевод строки в значении не превращается в лишнюю опцию curl"
  # Адрес и сессионный ключ Long Poll приезжают из сети. Конфиг curl читается
  # построчно: значение с переводом строки закрывает кавычку, и следующая строка
  # становится опцией — `output = …` пишет файл от root.
  run_bot_func poll_longpoll "https://lp.vk.com/wh42" 'k
output = /tmp/pwned' "175"
  assert_curl_was_called || { teardown; return; }
  if grep -qE '^[[:space:]]*output[[:space:]]*=' "$TMP/curl.cfg" 2>/dev/null; then
    fail "в конфиге curl появилась опция output — инъекция прошла: <<$(cat "$TMP/curl.cfg")>>"
  fi
  [ -e /tmp/pwned ] && fail "curl записал файл по подставленной опции"
  pass
  teardown
}

t_long_message_is_truncated_not_lost() {
  setup "слишком длинное сообщение обрезается и доходит, а не отвергается целиком"
  local long
  long="$(printf 'строка %s\n' $(seq 1 900))"
  run_bot_func send_message "$PEER" "$long"
  assert_vk_count 1 || { teardown; return; }
  grep -qF "обрезано" "$STUB_VK_OUT" || fail "длинный текст ушёл без пометки об обрезке — <<$(head -c 200 "$STUB_VK_OUT")>>"
  [ "$(wc -c < "$STUB_VK_OUT")" -lt 12000 ] || fail "текст не обрезан: $(wc -c < "$STUB_VK_OUT") байт"
  assert_err_contains "обрезано, полностью" && pass
  teardown
}

t_empty_library_is_loud() {
  setup "пустой vk-send.sh валит скрипт: читаемость файла — ещё не пригодность"
  arm_ram_alert
  : > "$MON/vk-send.sh"
  run_script server-monitor.sh
  [ "$RC" = "0" ] && fail "скрипт с пустой библиотекой отработал с нулём — отказ молчит"
  assert_vk_count 0 || { teardown; return; }
  [ -s "$TMP/docker.argv" ] && fail "скрипт пошёл проверять хост, хотя сообщить о находках нечем"
  assert_err_contains "vk_send_message" && pass
  teardown
}

t_missing_library_is_loud() {
  setup "пропавшая общая отправка валит скрипт, а не отключает алерты молча"
  arm_ram_alert
  rm -f "$MON/vk-send.sh"
  run_script server-monitor.sh
  [ "$RC" = "0" ] && fail "скрипт без vk-send.sh отработал с нулём — отказ молчит"
  assert_vk_count 0 || { teardown; return; }
  # И не притворяется, что мониторит: прогон, у которого нет способа сообщить,
  # не должен опрашивать контейнеры и делать вид, что всё проверено.
  [ -s "$TMP/docker.argv" ] && fail "скрипт пошёл проверять хост, хотя сообщить о находках нечем"
  assert_err_contains "vk-send.sh" && pass
  teardown
}

t_no_account_is_loud() {
  setup "пустой токен в конфиге — это сказанный вслух отказ, а не тихий пропуск"
  arm_ram_alert
  conf_set 'VK_TOKEN=""'
  run_script server-monitor.sh
  assert_vk_count 0 || { teardown; return; }
  assert_err_contains "УЧЁТКИ" && pass
  teardown
}

# ------------------------------------------------------------------ run ---

echo "Тесты отправки в VK (server-monitor, stats-report, vk-bot)"
for t in $(declare -F | awk '{print $3}' | grep '^t_' | grep -- "${VK_TEST_FILTER:-}"); do "$t"; done

echo
printf 'Пройдено: %d, провалено: %d\n' "$PASSED" "$FAILED"

# Сценарий, потерянный опечаткой в имени, иначе исчезает бесшумно, а набор
# рапортует «провалено: 0» — тот же тихий отказ, что и в самом мониторинге.
if [ -z "${VK_TEST_FILTER:-}" ]; then
  DECLARED=$(declare -F | awk '{print $3}' | grep -c '^t_')
  RAN=$((PASSED + FAILED))
  if [ "$RAN" -lt "$DECLARED" ]; then
    printf 'ОШИБКА: объявлено сценариев %d, отчиталось %d — какой-то не запустился\n' "$DECLARED" "$RAN"
    exit 1
  fi
fi

[ "$FAILED" -eq 0 ] || exit 1
