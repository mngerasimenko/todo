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
# Сторож по умолчанию короткий — теста «тело поднялось» хватает и восьми секунд.
# Сценариям, которые ждут выхода бота по потолку неудач, нужен запас: каждый
# вызов заглушки в Git Bash под Windows стоит около полутора секунд, и на
# Linux-раннере те же шаги проходят за доли секунды.
run_bot_body() {
  local secs="${1:-8}"
  PATH="$STUBS:$PATH" timeout "$secs" bash "$MON/vk-bot.sh" > "$TMP/run.out" 2> "$TMP/run.err"
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
  setup "stats-report: actuator промолчал — сводка не шлётся, и это слышно"
  export STUB_ACTUATOR=down
  run_script stats-report.sh
  assert_vk_count 0 || { teardown; return; }
  # Без этих двух проверок сценарий переживал бы возврат к прежнему молчаливому
  # `exit 0`: не пришедшая сводка неотличима от исправной тишины.
  assert_rc 1 || { teardown; return; }
  assert_err_contains "actuator" && pass
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
  assert_err_contains "обрезано до 3400" || { teardown; return; }
  # Полный текст в лог не льём намеренно: в суточной сводке это имена
  # пользователей, а /var/log читают все локальные учётки хоста.
  assert_err_lacks "строка 900" && pass
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
  [ "$RC" = "0" ] && { fail "прогон без токена отработал с нулём"; teardown; return; }
  assert_err_contains "нет учётки VK" && pass
  teardown
}

# Ветка «НЕТ УЧЁТКИ» внутри самой отправки: скрипты до неё теперь не доходят
# (проверка значений валит их раньше), но функция общая, и её зовут напрямую —
# без этого сценария путь остался бы непокрытым.
t_send_without_account_is_loud() {
  setup "vk_send_message без токена: отказ сказан вслух и код возврата ненулевой"
  PATH="$STUBS:$PATH" timeout 20 bash -c '
    . "$1"
    VK_TOKEN="" VK_PEER_ID="42"
    vk_send_message "текст"
  ' _ "$MON/vk-send.sh" > "$TMP/run.out" 2> "$TMP/run.err"
  RC=$?
  assert_rc 1 || { teardown; return; }
  assert_vk_count 0 || { teardown; return; }
  assert_err_contains "НЕТ УЧЁТКИ" && pass
  teardown
}

# ------------------------------------------------- пропавший monitor.conf ---

# Конфига нет в git: на пересобранном хосте его кладут руками, и забыть это
# легко. Без него пусты и токен, и пороги — сравнения падают на «integer
# expression expected» в лог, который никто не читает, а прогон выходит с нулём.
# То есть мониторинг выключен и молчит об этом ровно так же, как исправный.
t_server_monitor_missing_conf_is_loud() {
  setup "server-monitor: пропавший monitor.conf валит прогон, а не гасит пороги молча"
  rm -f "$CONF"
  run_script server-monitor.sh
  [ "$RC" = "0" ] && fail "прогон без конфига отработал с нулём — мониторинг выключен молча"
  assert_vk_count 0 || { teardown; return; }
  assert_err_contains "monitor.conf" && pass
  teardown
}

t_stats_report_missing_conf_is_loud() {
  setup "stats-report: пропавший monitor.conf валит прогон"
  rm -f "$CONF"
  run_script stats-report.sh
  [ "$RC" = "0" ] && fail "прогон без конфига отработал с нулём"
  assert_err_contains "monitor.conf" && pass
  teardown
}

t_vk_bot_missing_conf_exits() {
  setup "vk-bot: без monitor.conf бот выходит, а не остаётся живым в systemctl"
  rm -f "$CONF"
  run_bot_body
  [ "$RC" = "124" ] && { fail "бот не завершился: под Restart=always юнит выглядел бы работающим"; teardown; return; }
  assert_rc 1 || { teardown; return; }
  assert_err_contains "monitor.conf" && pass
  teardown
}

# ----------------------------------------------------- отказы Long Poll ---

# Протухший или отозванный токен — самый вероятный боевой отказ бота, и он же
# самый тихий: процесс не падает, `systemctl status` зелёный, команды владельца
# просто не доходят. Потолок неудач нужен, чтобы юнит дошёл до failed, как и
# обещает StartLimitBurst в его файле.
t_vk_bot_longpoll_rejected_gives_up() {
  setup "vk-bot: VK отвергает выдачу Long Poll — бот сдаётся, а не крутится живым"
  export STUB_LP=reject
  conf_set 'VK_LP_RETRY_SLEEP=0'
  conf_set 'VK_LP_MAX_FAILURES=2'
  conf_set 'VK_LP_POLL_SLEEP=0'
  conf_set 'VK_LP_DEAD_AFTER=0'
  run_bot_body 60
  [ "$RC" = "124" ] && { fail "бот крутится вечно: юнит остаётся active, отказ не виден"; teardown; return; }
  assert_rc 1 || { teardown; return; }
  assert_err_contains "VK отверг" && pass
  teardown
}

# Не-JSON в ответе опроса (502 от lp.vk.com, капча, страница провайдера) раньше
# не тормозил цикл вовсе: ни sleep, ни строки в journal — бот молотил VK
# непрерывно, съедая ядро машины, которую и сторожит.
t_vk_bot_longpoll_garbage_is_loud() {
  setup "vk-bot: неразбираемый ответ Long Poll слышен и не крутит горячий цикл"
  export STUB_LPPOLL=garbage
  conf_set 'VK_LP_RETRY_SLEEP=0'
  conf_set 'VK_LP_MAX_FAILURES=2'
  conf_set 'VK_LP_POLL_SLEEP=0'
  conf_set 'VK_LP_DEAD_AFTER=0'
  run_bot_body 60
  [ "$RC" = "124" ] && { fail "бот не завершился на мусорном ответе — горячий цикл без задержки"; teardown; return; }
  assert_err_contains "Long Poll" && pass
  teardown
}

# ---------------------------------------------------- обрезка сообщения ---

# Обрезка идёт по границам строк, и на тексте без переводов строки прежняя
# версия отдавала заголовок и «(обрезано)»: /logs с одной длинной строкой лога
# приходил владельцу пустым, а отправка при этом считалась успешной.
t_long_single_line_message_keeps_body() {
  setup "длинная одна строка: в ВК уходит тело, а не заголовок с «(обрезано)»"
  local long got
  long="$(printf 'x%.0s' $(seq 1 5000))"
  run_bot_func send_message "$PEER" "ЗАГОЛОВОК
${long}"
  assert_vk_count 1 || { teardown; return; }
  got=$(vk_text | wc -c)
  [ "$got" -ge 3000 ] || { fail "в ВК ушло $got байт при бюджете 3400 — тело потерялось"; teardown; return; }
  pass
  teardown
}

# Под cron локаль C, и bash режет байты, а не символы: хвост обрезанной
# кириллицы оставался недописанной последовательностью, а битый UTF-8 VK
# отвергает целиком — сообщение пропадало.
t_truncated_cyrillic_stays_valid_utf8() {
  setup "обрезка кириллицы не рвёт символ: битый UTF-8 VK отверг бы целиком"
  local long
  # Префикс нечётной длины обязателен: без него 3400 байт делятся на двухбайтную
  # «я» нацело, разрез приходится ровно на границу символа, и сценарий остаётся
  # зелёным даже без починки — проверено откатом 22.09.
  long="x$(printf 'я%.0s' $(seq 1 4000))"
  # Локаль cron — C, и в ней bash режет байты, а не символы: под UTF-8-локалью
  # разработчика этот дефект не воспроизводится вовсе.
  export LC_ALL=C
  run_bot_func send_message "$PEER" "$long"
  unset LC_ALL
  assert_vk_count 1 || { teardown; return; }
  if ! vk_text | iconv -f UTF-8 -t UTF-8 >/dev/null 2>&1; then
    fail "в ВК ушёл битый UTF-8 — VK отвергнет сообщение целиком"
    teardown; return
  fi
  pass
  teardown
}

# ------------------------------------------------- редакция и границы ---

# Вырезание токена из текста — половина инварианта «секрет не в логе», и до
# этого теста она не была закреплена ничем: выродив vk_redact в printf, набор
# по-прежнему был бы зелёным. Путь живой: /logs и /errors шлют владельцу сырой
# вывод docker logs.
t_token_in_message_is_redacted() {
  setup "токен в тексте сообщения вырезается, а не уезжает в диалог"
  run_bot_func send_message "$PEER" "в логе мелькнул ${TOKEN} — посмотри"
  assert_vk_count 1 || { teardown; return; }
  if vk_text | grep -qF -- "$TOKEN"; then
    fail "токен ушёл в ВК открытым текстом"
    teardown; return
  fi
  vk_text | grep -qF -- "<VK_TOKEN>" || { fail "нет метки редакции — вырезано не то"; teardown; return; }
  pass
  teardown
}

# Единственная граница доступа у бота, который исполняет docker restart от root.
t_vk_bot_ignores_foreign_peer() {
  setup "vk-bot: команда с чужого peer_id не исполняется"
  run_bot_func process_message 999 "/restart todo-app"
  assert_vk_count 0 || { teardown; return; }
  [ -s "$TMP/docker.argv" ] && { fail "чужая команда дошла до docker"; teardown; return; }
  pass
  teardown
}

# Не-JSON от api.vk.com (страница провайдера, 502) — это отказ: раньше ветка
# «ответ не разобран» не проверялась ни одним сценарием.
t_vk_garbage_response_is_rejected() {
  setup "нераспознанный ответ VK — отказ, а не успех"
  arm_ram_alert
  export STUB_VK=garbage
  run_script server-monitor.sh
  assert_err_contains "VK отверг" && pass
  teardown
}

# Валидный JSON, который не является ответом Long Poll: ошибка VK, ответ капчи,
# заглушка провайдера. У него есть разбираемое тело, поэтому проверка «оба поля
# пусты» его пропускала — и опрос возвращался мгновенно, без паузы и без строки
# в journal. Признак настоящего ответа один: в нём есть ts.
t_vk_bot_longpoll_json_without_ts_is_loud() {
  setup "vk-bot: JSON без ts — это отказ, а не «обновлений нет»"
  export STUB_LPPOLL=jsonerr
  conf_set 'VK_LP_MAX_FAILURES=2'
  conf_set 'VK_LP_POLL_SLEEP=0'
  conf_set 'VK_LP_DEAD_AFTER=0'
  run_bot_body 60
  [ "$RC" = "124" ] && { fail "бот не завершился — горячий цикл на ответе без ts"; teardown; return; }
  assert_err_contains "Long Poll" && pass
  teardown
}

# Мусор в ручке потолка раньше выключал бы сам потолок: сравнение падает с
# «integer expression expected» и трактуется как «ещё не пора».
t_vk_bot_bad_knob_falls_back_to_default() {
  setup "vk-bot: нечисловая ручка потолка не выключает потолок молча"
  export STUB_LP=reject
  conf_set 'VK_LP_MAX_FAILURES=abc'
  conf_set 'VK_LP_RETRY_SLEEP=0'
  conf_set 'VK_LP_DEAD_AFTER=0'
  run_bot_body 60
  [ "$RC" = "124" ] && { fail "бот крутится вечно: потолок выключен мусорным значением"; teardown; return; }
  assert_rc 1 && pass
  teardown
}

# Конфиг есть, но пустой или недописанный — обычное состояние пересобранного
# хоста, куда его кладут руками. Пороги тогда пусты, сравнения падают в лог,
# алерты не срабатывают ни разу, а прогон выходит с нулём.
t_server_monitor_empty_conf_is_loud() {
  setup "server-monitor: конфиг без порогов и токена валит прогон, а не гасит алерты"
  : > "$CONF"
  run_script server-monitor.sh
  [ "$RC" = "0" ] && fail "прогон с пустым конфигом отработал с нулём"
  assert_vk_count 0 || { teardown; return; }
  assert_err_contains "monitor.conf" && pass
  teardown
}

t_vk_bot_empty_conf_exits() {
  setup "vk-bot: пустой конфиг — выход, а не вечный цикл с пустым токеном"
  : > "$CONF"
  run_bot_body 30
  [ "$RC" = "124" ] && { fail "бот не завершился при пустом конфиге"; teardown; return; }
  assert_rc 1 && pass
  teardown
}

# Текст сообщения в лог не попадает вовсе: в суточной сводке имена новых
# пользователей стоят третьей строкой, то есть в любую «безопасную» выдержку с
# начала, а /var/log на обоих хостах читают все локальные учётки.
t_truncation_log_keeps_names_out() {
  setup "обрезка: в лог идёт длина и факт, а не текст сообщения"
  local long
  long="$(printf 'ПОЛЬЗОВАТЕЛЬ-%s
' $(seq 1 400))"
  run_bot_func send_message "$PEER" "$long"
  assert_vk_count 1 || { teardown; return; }
  assert_err_contains "обрезано" || { teardown; return; }
  assert_err_lacks "ПОЛЬЗОВАТЕЛЬ-1" && pass
  teardown
}

# Несущая строка переделки потолка — отметка живости на удачном опросе. Без неё
# бот выходил бы и при исправном канале, где обновлений просто нет: ложное
# срабатывание тут дороже самого отказа, потому что оно постоянное.
t_vk_bot_quiet_but_alive_channel_survives() {
  setup "vk-bot: исправный опрос без обновлений не считается смертью канала"
  export STUB_LPPOLL=ok
  conf_set 'VK_LP_DEAD_AFTER=1'
  conf_set 'VK_LP_POLL_SLEEP=0'
  run_bot_body 12
  [ "$RC" = "124" ] || fail "бот вышел с rc=$RC при живом опросе — потолок бьёт по исправному каналу"
  pass
  teardown
}

# failed=2 — это ответ VK, а не отказ: сессию перевыпускают, канал жив. Без
# отметки живости здесь устойчивый failed объявил бы мёртвым исправный VK ровно
# через VK_LP_DEAD_AFTER.
t_vk_bot_failed_two_is_a_live_channel() {
  setup "vk-bot: failed=2 — просьба перевыпустить сессию, а не смерть канала"
  export STUB_LPPOLL=failed2
  conf_set 'VK_LP_DEAD_AFTER=1'
  conf_set 'VK_LP_POLL_SLEEP=0'
  conf_set 'VK_LP_RETRY_SLEEP=0'
  run_bot_body 12
  [ "$RC" = "124" ] || fail "бот вышел с rc=$RC на failed=2 — исправный VK принят за мёртвый"
  pass
  teardown
}

# Запасной путь обрезки достижим только там, где нет iconv, — то есть в наборе
# он не исполнялся ни разу, и откат этой починки прошёл бы незамеченным.
t_utf8_cut_without_iconv_keeps_valid_utf8() {
  setup "обрезка без iconv тоже не оставляет недописанный символ"
  local out
  out="$(PATH="$STUBS:$PATH" timeout 20 bash -c '
    iconv() { return 127; }
    . "$1"
    LC_ALL=C
    long="x$(printf "я%.0s" $(seq 1 200))"
    vk_utf8_cut "$long" 101
  ' _ "$MON/vk-send.sh" 2>/dev/null)"
  [ -n "$out" ] || { fail "запасной путь отдал пустоту"; teardown; return; }
  printf '%s' "$out" | iconv -f UTF-8 -t UTF-8 >/dev/null 2>&1 \
    || { fail "запасной путь отдал битый UTF-8 — <<$(printf '%s' "$out" | tail -c 6 | od -An -tx1)>>"; teardown; return; }
  pass
  teardown
}

# ------------------------------------------------------------------ run ---

echo "Тесты отправки в VK (server-monitor, stats-report, vk-bot)"
SELECTED="$(declare -F | awk '{print $3}' | grep '^t_' | grep -- "${VK_TEST_FILTER:-}")"
if [ -n "${VK_TEST_FILTER:-}" ]; then
  echo "ФИЛЬТР: ${VK_TEST_FILTER} — прогон частичный"
  # Опечатка в фильтре иначе даёт «Пройдено: 0, провалено: 0» и код 0, то есть
  # подтверждает починку, которую никто не проверял.
  [ -n "$SELECTED" ] || { echo "ОШИБКА: фильтр «${VK_TEST_FILTER}» не выбрал ни одного сценария" >&2; exit 1; }
fi
for t in $SELECTED; do "$t"; done

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
