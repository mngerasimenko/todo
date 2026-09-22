#!/usr/bin/env bash
# Тесты monitoring/install-portfolio-monitor.sh — прежде всего его работы с
# root-crontab. На проде там живут бэкап, чужие пробы и reload certbot, и ошибка
# установщика уносит их молча.
#
# Установщик запускается из временной копии каталога monitoring/ с поддельными
# наборами тестов: так он не трогает рабочую копию своими sed/chmod, а сценарий
# «тесты красные» воспроизводится без поломки настоящих тестов. crontab, id и
# install подменены заглушками из stubs-install/.
#
# Запуск: bash monitoring/tests/install-portfolio-monitor.test.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REAL="$HERE/.."
STUBS="$HERE/stubs-install"

# Набор безопасен, только пока заглушки перехватывают вызовы. Заглушка без бита
# исполнения молча пропускается при поиске по PATH, и под root тест переписал бы
# настоящий crontab хоста — вместе со строкой reload'а certbot.
for s in crontab id install chmod; do
  if [ "$( PATH="$STUBS:$PATH"; command -v "$s" )" != "$STUBS/$s" ]; then
    echo "ОШИБКА: заглушка $s не перехватывает вызов (нет бита исполнения?) — набор не запускается" >&2
    exit 1
  fi
done

LEGACY_MON='*/5 * * * * /root/monitoring/external-monitor.sh'
LEGACY_CERT='0 */12 * * * docker exec certbot certbot renew --quiet && docker exec nginx-proxy nginx -s reload'

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
  unset STUB_CRONTAB 2>/dev/null || true
  SRC="$TMP/src"
  mkdir -p "$SRC/tests" "$TMP/conf" "$TMP/logrotate"
  cp "$REAL/install-portfolio-monitor.sh" "$REAL/portfolio-monitor.sh" "$REAL/certbot-renew.sh" \
     "$REAL/portfolio-monitor.logrotate" "$REAL/certbot-renew.logrotate" "$REAL/portfolio-targets.conf" "$SRC/"
  suite_result portfolio-monitor 0
  suite_result certbot-renew 0
}

teardown() { [ -n "${TMP:-}" ] && rm -rf "$TMP"; }

# Поддельный набор тестов: код выхода задаёт сценарий.
suite_result() { printf '#!/usr/bin/env bash\necho "набор %s"\nexit %s\n' "$1" "$2" > "$SRC/tests/$1.test.sh"; }

run_install() {
  PATH="$STUBS:$PATH" PM_CONF_DIR="$TMP/conf" PM_STATE_DIR="$TMP/state" PM_LOGROTATE_DIR="$TMP/logrotate" \
    bash "$SRC/install-portfolio-monitor.sh" "$1" > "$TMP/out" 2>&1
  RC=$?
}

cron_writes() { if [ -f "$TMP/crontab.writes" ]; then wc -l < "$TMP/crontab.writes" | tr -d ' '; else echo 0; fi; }
backups()     { local n; n=$(ls "$TMP/conf" | grep -c '^crontab\.bak') || n=0; echo "$n"; }

pass() { [ "$FAILED_IN_CASE" = "0" ] && { PASSED=$((PASSED + 1)); printf '  ok   %s\n' "$CURRENT"; }; }
fail() { FAILED=$((FAILED + 1)); FAILED_IN_CASE=1; printf '  FAIL %s\n       %s\n' "$CURRENT" "$1"; }

assert_rc() { [ "$RC" = "$1" ] || { fail "код возврата: ожидали $1, получили $RC — <<$(cat "$TMP/out")>>"; return 1; }; return 0; }

assert_out_contains() {
  grep -qF -- "$1" "$TMP/out" || { fail "в выводе установщика нет «$1» — <<$(cat "$TMP/out")>>"; return 1; }
  return 0
}

assert_out_lacks() {
  grep -qF -- "$1" "$TMP/out" && { fail "в выводе установщика не должно быть «$1» — <<$(cat "$TMP/out")>>"; return 1; }
  return 0
}

assert_cron_has() {
  grep -qF -- "$1" "$TMP/crontab" 2>/dev/null || { fail "в crontab нет «$1» — <<$(cat "$TMP/crontab" 2>/dev/null)>>"; return 1; }
  return 0
}

assert_cron_lacks() {
  grep -qF -- "$1" "$TMP/crontab" 2>/dev/null && { fail "в crontab не должно быть «$1» — <<$(cat "$TMP/crontab")>>"; return 1; }
  return 0
}

# Ровно N строк с подстрокой: дубль строки проходит проверку «строка есть».
assert_cron_count() {
  local want="$1" got
  got=$(grep -cF -- "$2" "$TMP/crontab" 2>/dev/null) || got=0
  [ "$got" = "$want" ] || { fail "строк с «$2»: ожидали $want, получили $got — <<$(cat "$TMP/crontab" 2>/dev/null)>>"; return 1; }
  return 0
}

assert_writes() {
  [ "$(cron_writes)" = "$1" ] || { fail "crontab переписан $(cron_writes) раз, ждали $1"; return 1; }
  return 0
}

# ------------------------------------------------------------- сценарии ----

t_external_replaces_legacy_monitor_and_keeps_backup() {
  setup "стейдж: старая опрашивалка снята, offsite-бэкап на месте, бэкап crontab сделан"
  printf '%s\n%s\n' "$LEGACY_MON" '0 4 * * * /root/monitoring/offsite-backup.sh >> /root/backups/offsite-backup.log 2>&1' > "$TMP/crontab"
  run_install external
  assert_rc 0 && assert_cron_has "offsite-backup.sh" && assert_cron_lacks "external-monitor.sh" \
    && assert_cron_count 1 "portfolio-monitor.sh" || { teardown; return; }
  [ "$(backups)" = "1" ] || fail "ждали один бэкап crontab, нашли $(backups)"
  pass
  teardown
}

t_rerun_does_not_touch_crontab_or_backups() {
  setup "повторная установка: crontab уже в нужном виде — не переписывается, бэкап не плодится"
  printf '0 4 * * * /root/monitoring/offsite-backup.sh\n' > "$TMP/crontab"
  run_install external
  run_install external
  assert_rc 0 && assert_writes 1 && assert_cron_count 1 "portfolio-monitor.sh" || { teardown; return; }
  [ "$(backups)" = "1" ] || fail "бэкапов crontab $(backups), ждали один: второй деплой затёр бы исходную таблицу"
  pass
  teardown
}

t_peer_swaps_legacy_certbot_line_and_keeps_foreign_jobs() {
  setup "прод: старый reload certbot заменён certbot-renew.sh из root-копии, чужие задачи целы"
  cat > "$TMP/crontab" <<EOF
$LEGACY_CERT
0 3 * * * /root/monitoring/backup.sh >> /root/backups/backup.log 2>&1
0 4 * * * /home/deploy/todo/monitoring/stats-report.sh >> /var/log/stats-report.log 2>&1
*/5 * * * * /home/deploy/todo/monitoring/server-monitor.sh    >> /var/log/server-monitor.log 2>&1
30 3 * * 1 /root/monitoring/restore-drill.sh >> /root/backups/restore-drill.log 2>&1
0 12 * * * docker exec vpscan-app tsx /app/src/scraper/run-local.ts >> /var/log/vpscan-scrape-local.log 2>&1
*/5 * * * * /root/replyai-monitoring/replyai-probe.sh >> /var/log/replyai-probe.log 2>&1
EOF
  run_install peer
  assert_rc 0 && assert_cron_lacks "certbot certbot renew --quiet &&" \
    && assert_cron_has "$TMP/conf/certbot-renew.sh" && assert_cron_has "$TMP/conf/portfolio-monitor.sh" \
    && assert_cron_has "backup.sh" && assert_cron_has "stats-report.sh" && assert_cron_has "server-monitor.sh" \
    && assert_cron_has "restore-drill.sh" && assert_cron_has "vpscan-app" && assert_cron_has "replyai-probe.sh" \
    || { teardown; return; }
  [ -f "$TMP/conf/portfolio-monitor.sh" ] && [ -f "$TMP/conf/certbot-renew.sh" ] \
    || fail "root-копии скриптов не положены в $TMP/conf — <<$(ls "$TMP/conf")>>"
  pass
  teardown
}

t_peer_rerun_is_stable() {
  setup "прод: повторная установка не переписывает crontab"
  printf '%s\n' "$LEGACY_CERT" > "$TMP/crontab"
  run_install peer
  run_install peer
  assert_rc 0 && assert_writes 1 && assert_cron_count 1 "certbot-renew.sh" && pass
  teardown
}

t_peer_renew_ok_stamp_created_once_and_path_passed_to_cron() {
  setup "прод: отметка certbot-renew создаётся при установке, не перезаписывается, её путь уходит в строку cron"
  run_install peer
  assert_rc 0 && assert_cron_has "RENEW_OK_FILE=$TMP/state/certbot-renew.ok " || { teardown; return; }
  [ -s "$TMP/state/certbot-renew.ok" ] || { fail "отметка certbot-renew.ok не создана"; teardown; return; }
  printf '123\n' > "$TMP/state/certbot-renew.ok"
  run_install peer
  grep -qx '123' "$TMP/state/certbot-renew.ok" || fail "повторная установка перезаписала отметку — <<$(cat "$TMP/state/certbot-renew.ok")>>"
  pass
  teardown
}

t_lookalike_foreign_lines_survive() {
  setup "чужие строки, похожие на заменяемые (другой external-monitor, certbot renew --cert-name, чужая метка), целы"
  cat > "$TMP/crontab" <<EOF
$LEGACY_CERT
*/10 * * * * /root/vpscan-monitoring/external-monitor.sh >> /var/log/vpscan-ext.log 2>&1
0 5 * * * docker exec certbot certbot renew --cert-name clickmebattle.keepware.ru --quiet
0 1 * * * /opt/other/job.sh # portfolio-monitor:managed-by-other
EOF
  run_install peer
  assert_rc 0 && assert_cron_has "/root/vpscan-monitoring/external-monitor.sh" \
    && assert_cron_has "--cert-name clickmebattle.keepware.ru" \
    && assert_cron_has "portfolio-monitor:managed-by-other" \
    && assert_cron_lacks "certbot certbot renew --quiet &&" && pass
  teardown
}

t_legacy_line_with_extra_whitespace_is_replaced() {
  setup "старая строка с лишними пробелами и табом всё равно снимается — иначе на стейдже крутились бы две опрашивалки"
  printf '*/5  *  * * *\t/root/monitoring/external-monitor.sh   \n' > "$TMP/crontab"
  run_install external
  assert_rc 0 && assert_cron_lacks "external-monitor.sh" && assert_cron_count 1 "portfolio-monitor.sh" && pass
  teardown
}

t_commented_managed_line_is_left_alone() {
  setup "закомментированная строка опрашивалки не снимается молча — остаётся с предупреждением «выключать флагом»"
  printf '# */5 * * * * /usr/bin/timeout 280 /x/portfolio-monitor.sh >> /var/log/portfolio-monitor.log 2>&1 # portfolio-monitor:managed\n' > "$TMP/crontab"
  run_install external
  assert_rc 0 && assert_cron_has "# */5 * * * * /usr/bin/timeout 280 /x/portfolio-monitor.sh" \
    && assert_out_contains "закомментирован" && pass
  teardown
}

t_external_role_keeps_certbot_line() {
  setup "стейдж не снимает строку certbot: certbot-renew.sh там не ставится"
  printf '%s\n' "$LEGACY_CERT" > "$TMP/crontab"
  run_install external
  assert_rc 0 && assert_cron_has "certbot certbot renew --quiet &&" && pass
  teardown
}

t_env_and_comment_lines_survive_and_rerun_is_stable() {
  setup "строки окружения, комментарии и пустые строки целы, повторная установка ничего не пишет"
  cat > "$TMP/crontab" <<'EOF'
MAILTO=""
# ночной бэкап
0 4 * * * /root/monitoring/offsite-backup.sh

PATH=/usr/local/bin:/usr/bin:/bin
EOF
  run_install external
  run_install external
  assert_rc 0 && assert_writes 1 && assert_cron_has 'MAILTO=""' && assert_cron_has "# ночной бэкап" \
    && assert_cron_has "PATH=/usr/local/bin" && pass
  teardown
}

t_unreadable_crontab_is_not_overwritten() {
  setup "crontab -l упал не из-за «нет crontab» — таблица не переписывается"
  printf '0 4 * * * /root/monitoring/offsite-backup.sh\n' > "$TMP/crontab"
  cp "$TMP/crontab" "$TMP/crontab.orig"
  export STUB_CRONTAB=read_error
  run_install external
  assert_rc 1 && assert_out_contains "crontab -l вернул ошибку" || { teardown; return; }
  cmp -s "$TMP/crontab" "$TMP/crontab.orig" || fail "crontab изменён — <<$(cat "$TMP/crontab")>>"
  pass
  teardown
}

t_crontab_write_failure_is_loud() {
  setup "crontab отверг таблицу — установка падает, а не рапортует «установлено»"
  printf '0 4 * * * /root/monitoring/offsite-backup.sh\n' > "$TMP/crontab"
  export STUB_CRONTAB=write_error
  run_install external
  assert_rc 1 && assert_out_contains "crontab отверг" && pass
  teardown
}

t_crontab_written_differently_is_loud() {
  setup "crontab принял таблицу, а записал другое — установка падает на сверке"
  printf '0 4 * * * /root/monitoring/offsite-backup.sh\n' > "$TMP/crontab"
  export STUB_CRONTAB=write_garbled
  run_install external
  assert_rc 1 && assert_out_contains "не совпал" && pass
  teardown
}

t_first_install_on_empty_crontab() {
  setup "crontab пуст — опрашивалка встаёт, бэкапить нечего"
  run_install external
  assert_rc 0 && assert_cron_count 1 "portfolio-monitor.sh" || { teardown; return; }
  [ "$(backups)" = "0" ] || fail "бэкап пустой таблицы не нужен, нашли $(backups)"
  pass
  teardown
}

t_red_tests_abort_and_leave_crontab() {
  setup "тесты опрашивалки красные — установка прервана, crontab не тронут"
  printf '0 4 * * * /root/monitoring/offsite-backup.sh\n' > "$TMP/crontab"
  cp "$TMP/crontab" "$TMP/crontab.orig"
  suite_result portfolio-monitor 1
  run_install external
  assert_rc 1 && assert_writes 0 && assert_out_contains "тесты portfolio-monitor не прошли" || { teardown; return; }
  cmp -s "$TMP/crontab" "$TMP/crontab.orig" || fail "crontab изменён — <<$(cat "$TMP/crontab")>>"
  pass
  teardown
}

t_kill_switch_removes_managed_line() {
  setup "выключатель: строка опрашивалки снята и не возвращается деплоем, соседние задачи целы"
  printf '0 4 * * * /root/monitoring/offsite-backup.sh\n' > "$TMP/crontab"
  run_install external
  : > "$TMP/conf/portfolio-monitor.disabled"
  run_install external
  assert_rc 0 && assert_cron_lacks "portfolio-monitor.sh" && assert_cron_has "offsite-backup.sh" \
    && assert_out_contains "выключ" && pass
  teardown
}

t_kill_switch_works_with_red_tests() {
  setup "выключатель срабатывает и при красных тестах — ровно тот случай, ради которого он нужен"
  printf '0 4 * * * /root/monitoring/offsite-backup.sh\n' > "$TMP/crontab"
  run_install external
  suite_result portfolio-monitor 1
  : > "$TMP/conf/portfolio-monitor.disabled"
  run_install external
  assert_rc 0 && assert_cron_lacks "portfolio-monitor.sh" && pass
  teardown
}

t_kill_switch_keeps_legacy_monitor_line() {
  setup "выключатель не снимает старую external-monitor.sh: иначе откат оставил бы стейдж без наблюдения"
  printf '%s\n0 4 * * * /root/monitoring/offsite-backup.sh\n' "$LEGACY_MON" > "$TMP/crontab"
  : > "$TMP/conf/portfolio-monitor.disabled"
  run_install external
  assert_rc 0 && assert_cron_has "/root/monitoring/external-monitor.sh" && assert_cron_lacks "portfolio-monitor.sh" && pass
  teardown
}

t_kill_switch_keeps_certbot_renew_on_peer() {
  setup "выключатель на проде не трогает certbot-renew.sh — старая строка reload уже снята"
  printf '%s\n' "$LEGACY_CERT" > "$TMP/crontab"
  : > "$TMP/conf/portfolio-monitor.disabled"
  run_install peer
  assert_rc 0 && assert_cron_has "certbot-renew.sh" && assert_cron_lacks "portfolio-monitor.sh" \
    && assert_cron_lacks "certbot certbot renew --quiet &&" && pass
  teardown
}

t_removed_flag_restores_monitor_line() {
  setup "выключатель убран — следующая установка возвращает строку опрашивалки, одну"
  run_install external
  : > "$TMP/conf/portfolio-monitor.disabled"
  run_install external
  rm -f "$TMP/conf/portfolio-monitor.disabled"
  run_install external
  assert_rc 0 && assert_cron_count 1 "portfolio-monitor.sh" && pass
  teardown
}

t_existing_config_is_not_overwritten() {
  setup "готовый конфиг хоста не перезаписывается"
  printf 'ROLE=external\nCUSTOM_MARK=1\n' > "$TMP/conf/portfolio-monitor.conf"
  run_install external
  assert_rc 0 || { teardown; return; }
  grep -q '^CUSTOM_MARK=1$' "$TMP/conf/portfolio-monitor.conf" || fail "конфиг перезаписан — <<$(cat "$TMP/conf/portfolio-monitor.conf")>>"
  pass
  teardown
}

t_new_config_holds_only_host_values() {
  setup "новый конфиг прода — только значения хоста: пороги, список сертов и путь отметки не замораживаются"
  run_install peer
  assert_rc 0 || { teardown; return; }
  local c="$TMP/conf/portfolio-monitor.conf"
  grep -q '^ROLE=peer$' "$c" || fail "в конфиге нет ROLE=peer — <<$(cat "$c")>>"
  grep -q '^COPY_DRIFT_CHECK=' "$c" || fail "в конфиге нет COPY_DRIFT_CHECK — <<$(cat "$c")>>"
  grep -qE '^(CERT_WARN_DAYS|CERT_DRIFT_DOMAINS|RENEW_OK_FILE)=' "$c" \
    && fail "в конфиге заморожено значение по умолчанию — <<$(cat "$c")>>"
  pass
  teardown
}

t_state_dir_from_config_is_used() {
  setup "STATE_DIR из конфига хоста: отметка продления и путь в строке cron берутся оттуда"
  printf 'ROLE=peer\nVK_CONF_FILE=%s/conf/monitor.conf\nSTATE_DIR=%s/custom-state\n' "$TMP" "$TMP" > "$TMP/conf/portfolio-monitor.conf"
  run_install peer
  assert_rc 0 && assert_cron_has "RENEW_OK_FILE=$TMP/custom-state/certbot-renew.ok " || { teardown; return; }
  [ -s "$TMP/custom-state/certbot-renew.ok" ] \
    || fail "отметка не создана в каталоге из конфига — <<$(ls "$TMP/custom-state" 2>&1)>>"
  pass
  teardown
}

t_managed_tag_not_at_line_end_is_flagged() {
  setup "метка не в конце строки: строку не трогаем, но предупреждаем — иначе опрашивалка задвоится молча"
  printf '*/5 * * * * /usr/bin/timeout 280 /x/portfolio-monitor.sh >> /var/log/portfolio-monitor.log 2>&1 # portfolio-monitor:managed # выкл 15.09\n' > "$TMP/crontab"
  run_install external
  assert_rc 0 && assert_cron_has "выкл 15.09" && assert_out_contains "не в каноничной форме" && pass
  teardown
}

t_commented_line_warning_says_no_replacement_under_flag() {
  setup "выключатель и закомментированная строка: предупреждение не обещает поставить свою"
  printf '# */5 * * * * /usr/bin/timeout 280 /x/portfolio-monitor.sh >> /var/log/portfolio-monitor.log 2>&1 # portfolio-monitor:managed\n' > "$TMP/crontab"
  : > "$TMP/conf/portfolio-monitor.disabled"
  run_install external
  assert_rc 0 && assert_out_contains "закомментирована" || { teardown; return; }
  grep -qF "ставит свою" "$TMP/out" \
    && fail "предупреждение обещает поставить строку, хотя выключатель её снимает — <<$(cat "$TMP/out")>>"
  pass
  teardown
}

t_commented_certbot_line_warning_names_it_correctly() {
  setup "закомментирована строка продления — предупреждение называет её, а не опрашивалку"
  printf '# 17 * * * * RENEW_OK_FILE=/var/lib/portfolio-monitor/certbot-renew.ok /root/monitoring/certbot-renew.sh >> /var/log/certbot-renew.log 2>&1 # portfolio-monitor:managed\n' > "$TMP/crontab"
  run_install peer
  assert_rc 0 && assert_out_contains "продлени" && pass
  teardown
}

# Git Bash считает исполняемым любой файл с shebang, поэтому сценарии про снятый
# бит там не могут упасть — они пропускаются с отметкой. На Linux они настоящие.
exec_bit_is_honored() {
  local p="$TMP/exec-probe.sh"
  printf '#!/bin/sh\n' > "$p"
  /usr/bin/chmod 644 "$p"
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

t_missing_exec_bit_is_loud() {
  setup "скрипт, который зовёт cron, не исполняемый — установка падает с внятной причиной"
  if ! exec_bit_is_honored; then skip_case "окружение не различает бит исполнения"; teardown; return; fi
  /usr/bin/chmod 644 "$SRC/portfolio-monitor.sh"
  export STUB_CHMOD_NOOP=1
  run_install external
  unset STUB_CHMOD_NOOP
  assert_rc 1 && assert_out_contains "не исполняемый" && pass
  teardown
}

t_missing_exec_bit_with_flag_still_removes_line() {
  setup "выключатель снимает строку, даже если скрипт не исполняемый: проверка не должна мешать выключению"
  if ! exec_bit_is_honored; then skip_case "окружение не различает бит исполнения"; teardown; return; fi
  printf '0 4 * * * /root/monitoring/offsite-backup.sh\n' > "$TMP/crontab"
  run_install external
  : > "$TMP/conf/portfolio-monitor.disabled"
  /usr/bin/chmod 644 "$SRC/portfolio-monitor.sh"
  export STUB_CHMOD_NOOP=1
  run_install external
  unset STUB_CHMOD_NOOP
  assert_rc 0 && assert_cron_lacks "portfolio-monitor.sh" && pass
  teardown
}

t_relative_state_dir_in_config_is_loud() {
  setup "относительный STATE_DIR в конфиге — установка падает: иначе опрашивалка и продление разойдутся по каталогам"
  printf 'ROLE=peer\nVK_CONF_FILE=%s/conf/monitor.conf\nSTATE_DIR=relative/state\n' "$TMP" > "$TMP/conf/portfolio-monitor.conf"
  run_install peer
  assert_rc 1 && assert_out_contains "не абсолютный" && pass
  teardown
}

t_state_dir_with_space_is_loud() {
  setup "STATE_DIR с пробелом — установка падает: строка cron из такого пути ломается"
  printf 'ROLE=peer\nVK_CONF_FILE=%s/conf/monitor.conf\nSTATE_DIR="%s/my state"\n' "$TMP" "$TMP" > "$TMP/conf/portfolio-monitor.conf"
  run_install peer
  assert_rc 1 && assert_out_contains "пробел" && pass
  teardown
}

t_unparseable_config_is_loud() {
  setup "конфиг не разбирается — установка падает, а не идёт как ни в чём не бывало"
  printf 'ROLE=peer\nVK_CONF_FILE=%s/conf/monitor.conf\nSTATE_DIR=$(\n' "$TMP" > "$TMP/conf/portfolio-monitor.conf"
  run_install peer
  assert_rc 1 && assert_out_contains "не разобрался" && pass
  teardown
}

t_kill_switch_wins_over_broken_state_dir() {
  setup "выключатель важнее негодного STATE_DIR: строку снимаем, а не падаем"
  printf '0 4 * * * /root/monitoring/offsite-backup.sh\n' > "$TMP/crontab"
  run_install external
  assert_rc 0 && assert_cron_count 1 "portfolio-monitor.sh" || { teardown; return; }
  printf 'ROLE=external\nVK_CONF_FILE=%s/conf/monitor.conf\nSTATE_DIR=relative/state\n' "$TMP" > "$TMP/conf/portfolio-monitor.conf"
  : > "$TMP/conf/portfolio-monitor.disabled"
  run_install external
  assert_rc 0 && assert_cron_lacks "portfolio-monitor.sh" && assert_cron_has "offsite-backup.sh" || { teardown; return; }
  # Предупреждение — единственный признак, что на хосте сломанный конфиг. Без
  # ассерта его удаление прошло бы незамеченным, и мягкая ветка стала бы молчаливой.
  assert_out_contains "ВНИМАНИЕ" && assert_out_contains "не абсолютный" \
    && assert_out_contains "каталог состояния беру по умолчанию" \
    && assert_out_contains "$TMP/state" && pass
  teardown
}

t_config_with_nonzero_last_command_installs() {
  setup "конфиг, кончающийся командой с ненулевым кодом, — не «сломанный конфиг»: установка идёт"
  printf 'ROLE=external\nVK_CONF_FILE=%s/conf/monitor.conf\nSTATE_DIR=%s/state\n[ -f /nonexistent-portfolio-monitor ] && . /nonexistent-portfolio-monitor\n' "$TMP" "$TMP" > "$TMP/conf/portfolio-monitor.conf"
  run_install external
  # Точка возвращает код ПОСЛЕДНЕЙ команды файла. Принимая его за «конфиг не
  # разобрался», установщик падал бы, а с ним и джоба deploy-staging на каждом мерже.
  assert_rc 0 && assert_cron_count 1 "portfolio-monitor.sh" && assert_out_lacks "не разобрался" && pass
  teardown
}

t_config_with_early_exit_is_loud() {
  setup "конфиг с exit посередине: опрашивалка молча выйдет с кодом 0 — установщик обязан сказать"
  printf 'ROLE=external\nSTATE_DIR=%s/state\nexit 0\nVK_CONF_FILE=%s/conf/monitor.conf\n' "$TMP" "$TMP" > "$TMP/conf/portfolio-monitor.conf"
  run_install external
  assert_rc 1 && assert_out_contains "оборвался" && pass
  teardown
}

t_quoted_vk_conf_file_is_read() {
  setup "VK_CONF_FILE в кавычках читается как путь, а не вместе с кавычками"
  printf 'VK_TOKEN=x\n' > "$TMP/conf/monitor.conf"
  printf 'ROLE=external\nSTATE_DIR=%s/state\nexport VK_CONF_FILE="%s/conf/monitor.conf"\n' "$TMP" "$TMP" > "$TMP/conf/portfolio-monitor.conf"
  run_install external
  # Ложная тревога на сторожевом скрипте приучает не читать его предупреждения.
  assert_rc 0 && assert_out_lacks "не читается или без VK_TOKEN" && pass
  teardown
}

t_state_dir_with_percent_is_loud() {
  setup "STATE_DIR с процентом: в поле команды crontab это перевод строки — строка оборвалась бы молча"
  printf 'ROLE=external\nVK_CONF_FILE=%s/conf/monitor.conf\nSTATE_DIR=%s/state%%old\n' "$TMP" "$TMP" > "$TMP/conf/portfolio-monitor.conf"
  run_install external
  assert_rc 1 && assert_out_contains "содержит %" && pass
  teardown
}

t_peer_kill_switch_broken_state_dir_keeps_renew_line() {
  setup "роль peer при выключателе и негодном STATE_DIR: строка продления ставится, и о её каталоге сказано"
  run_install peer
  assert_rc 0 || { teardown; return; }
  printf 'ROLE=peer\nVK_CONF_FILE=%s/conf/monitor.conf\nSTATE_DIR=relative/state\n' "$TMP" > "$TMP/conf/portfolio-monitor.conf"
  : > "$TMP/conf/portfolio-monitor.disabled"
  run_install peer
  # Выключатель снимает строку опрашивалки, но НЕ строку продления: установщик
  # всё равно решает, в каком каталоге ей искать отметку, и обязан это назвать.
  assert_rc 0 && assert_cron_lacks "portfolio-monitor.sh" \
    && assert_cron_has "RENEW_OK_FILE=$TMP/state/certbot-renew.ok" \
    && assert_out_contains "Строка продления" && pass
  teardown
}

t_cron_line_carries_the_wrapper_timeout() {
  setup "строка cron несёт потолок обёртки: одно число и в timeout, и в окружении опрашивалки"
  run_install external
  # Опрашивалка сверяет свой бюджет с этим потолком. Возьми она его из конфига —
  # конфиг мог бы отключить проверку, которая его же и сторожит; поэтому число
  # пишется здесь, один раз, и попадает в строку дважды.
  assert_rc 0 && assert_cron_has "CRON_TIMEOUT_SEC=280 /usr/bin/timeout 280 " && pass
  teardown
}

t_config_with_set_e_is_loud() {
  setup "set -e в конфиге: опрашивалка унаследует его в главной оболочке и умрёт на первой штатной неудаче"
  printf 'ROLE=external\nset -e\nVK_CONF_FILE=%s/conf/monitor.conf\nSTATE_DIR=%s/state\n' "$TMP" "$TMP" > "$TMP/conf/portfolio-monitor.conf"
  run_install external
  assert_rc 1 && assert_out_contains "режим оболочки" && pass
  teardown
}

t_config_with_exit_trap_installs() {
  setup "trap EXIT в конфиге печатает после маркера — это не «конфиг оборвался», установка идёт"
  printf 'ROLE=external\nVK_CONF_FILE=%s/conf/monitor.conf\nSTATE_DIR=%s/state\ntrap "echo conf loaded" EXIT\n' "$TMP" "$TMP" > "$TMP/conf/portfolio-monitor.conf"
  run_install external
  assert_rc 0 && assert_cron_count 1 "portfolio-monitor.sh" && assert_out_lacks "оборвался" && pass
  teardown
}

t_second_sourcing_does_not_leak_state_dir() {
  setup "второй сорсинг тоже снимает STATE_DIR: иначе установщик и опрашивалка видят разные пути"
  printf 'VK_TOKEN=x\n' > "$TMP/conf/monitor.conf"
  # STATE_DIR в конфиге НЕ задан: опрашивалка сорсит его до присвоения умолчания и
  # получит подстановку по умолчанию. Установщик, не сняв переменную, подставит свою.
  printf 'ROLE=external\nVK_CONF_FILE="${STATE_DIR:-%s/conf}/monitor.conf"\n' "$TMP" > "$TMP/conf/portfolio-monitor.conf"
  run_install external
  assert_rc 0 && assert_out_lacks "не читается или без VK_TOKEN" && pass
  teardown
}

# ------------------------------------------ локальный прогон vpscan (прод) ---

# Как строка стоит в root-crontab прода (сверено 21.09.2026).
VPSCAN_LINE='0 12 * * * docker exec vpscan-app tsx /app/src/scraper/run-local.ts >> /var/log/vpscan-scrape-local.log 2>&1'

t_peer_installs_vpscan_local_cron() {
  setup "прод: строка локального прогона vpscan ставится с меткой — после пересборки хоста её есть чем вернуть"
  run_install peer
  assert_rc 0 && assert_cron_count 1 "run-local.ts" || { teardown; return; }
  grep -qF -- "$VPSCAN_LINE # portfolio-monitor:managed" "$TMP/crontab" \
    || fail "строка vpscan не в каноничном виде с меткой — <<$(cat "$TMP/crontab")>>"
  pass
  teardown
}

t_peer_adopts_existing_vpscan_line_without_duplicating() {
  setup "прод: стоявшая руками строка vpscan заменяется своей, а не задваивается"
  printf '%s\n' "$VPSCAN_LINE" > "$TMP/crontab"
  run_install peer
  assert_rc 0 && assert_cron_count 1 "run-local.ts" && assert_out_lacks "не совпавшая с ожидаемой" && pass
  teardown
}

t_peer_vpscan_line_with_extra_whitespace_is_replaced() {
  setup "прод: строка vpscan с лишними пробелами и табом тоже снимается — иначе прогон задвоился бы"
  printf '0 12  *  * *\tdocker exec vpscan-app tsx /app/src/scraper/run-local.ts >> /var/log/vpscan-scrape-local.log 2>&1   \n' > "$TMP/crontab"
  run_install peer
  assert_rc 0 && assert_cron_count 1 "run-local.ts" && pass
  teardown
}

t_peer_rerun_keeps_single_vpscan_line() {
  setup "прод: повторная установка не пишет crontab заново и не плодит вторую строку vpscan"
  printf '%s\n' "$VPSCAN_LINE" > "$TMP/crontab"
  run_install peer
  run_install peer
  assert_rc 0 && assert_writes 1 && assert_cron_count 1 "run-local.ts" && pass
  teardown
}

t_external_does_not_touch_vpscan_line() {
  setup "стейдж: строку vpscan не ставит и чужую не снимает — контейнера там нет"
  printf '%s\n' "$VPSCAN_LINE" > "$TMP/crontab"
  run_install external
  assert_rc 0 && assert_cron_count 1 "run-local.ts" \
    && assert_cron_lacks "run-local.ts >> /var/log/vpscan-scrape-local.log 2>&1 # portfolio-monitor:managed" && pass
  teardown
}

t_disabled_flag_keeps_vpscan_line() {
  setup "выключатель наблюдения не выключает чужой ежедневный прогон vpscan"
  run_install peer
  : > "$TMP/conf/portfolio-monitor.disabled"
  run_install peer
  assert_rc 0 && assert_cron_lacks "portfolio-monitor.sh >>" && assert_cron_count 1 "run-local.ts" && pass
  teardown
}

# --------------------------------------------- права конфига с токеном VK ---

# Под Windows файловые права не меняются вовсе (chmod 600 оставляет 644) —
# сценарий «уже 600, не трогаем» там воспроизвести нечем.
mode_is_honored() {
  local p="$TMP/mode.probe"
  : > "$p"
  /usr/bin/chmod 644 "$p" 2>/dev/null
  /usr/bin/chmod 600 "$p" 2>/dev/null
  [ "$(stat -c '%a' "$p" 2>/dev/null)" = "600" ]
}

t_vk_conf_mode_is_tightened() {
  setup "конфиг с токеном VK: права 644 сужаются до 600 при каждой установке"
  printf 'VK_TOKEN=x\n' > "$TMP/conf/monitor.conf"
  /usr/bin/chmod 644 "$TMP/conf/monitor.conf" 2>/dev/null
  run_install external
  assert_rc 0 && assert_out_contains "права сужены до 600" || { teardown; return; }
  grep -q -- "600 $TMP/conf/monitor.conf" "$TMP/chmod.argv" 2>/dev/null \
    || fail "chmod 600 на конфиг не звался — <<$(cat "$TMP/chmod.argv" 2>/dev/null)>>"
  pass
  teardown
}

t_vk_conf_already_tight_is_left_alone() {
  setup "конфиг уже 600 — установщик его не трогает и не шумит"
  printf 'VK_TOKEN=x\n' > "$TMP/conf/monitor.conf"
  /usr/bin/chmod 600 "$TMP/conf/monitor.conf" 2>/dev/null
  if ! mode_is_honored; then skip_case "окружение не различает права файла"; teardown; return; fi
  run_install external
  assert_rc 0 && assert_out_lacks "права сужены до 600" && pass
  teardown
}

t_vpscan_line_with_leading_space_is_replaced() {
  setup "прод: строка vpscan с ведущим пробелом снимается — cron такую принимает, и рядом встала бы вторая"
  printf ' %s
' "$VPSCAN_LINE" > "$TMP/crontab"
  run_install peer
  assert_rc 0 && assert_cron_count 1 "run-local.ts" && pass
  teardown
}

t_second_conf_copy_beside_scripts_is_tightened() {
  setup "вторая копия конфига рядом со скриптами тоже сужается: на проде из неё читают три отправителя"
  printf 'VK_TOKEN=x
' > "$SRC/monitor.conf"
  /usr/bin/chmod 644 "$SRC/monitor.conf" 2>/dev/null
  run_install external
  assert_rc 0 || { teardown; return; }
  grep -q -- "600 $SRC/monitor.conf" "$TMP/chmod.argv" 2>/dev/null     || fail "chmod 600 на копию рядом со скриптами не звался — <<$(cat "$TMP/chmod.argv" 2>/dev/null)>>"
  pass
  teardown
}

t_symlinked_conf_is_not_touched() {
  setup "конфиг-симлинк не трогаем: цель может лежать где угодно"
  printf 'VK_TOKEN=x
' > "$TMP/real-conf"
  # В Git Bash без winsymlinks ln -s делает копию: проверяем результат, а не код
  # возврата, иначе тест «не трогаем симлинк» шёл бы по обычному файлу.
  ln -s "$TMP/real-conf" "$TMP/conf/monitor.conf" 2>/dev/null
  if [ ! -L "$TMP/conf/monitor.conf" ]; then
    skip_case "симлинки в этом окружении не создаются"; teardown; return
  fi
  run_install external
  assert_rc 0 && assert_out_contains "симлинк" || { teardown; return; }
  grep -q -- "600 $TMP/conf/monitor.conf" "$TMP/chmod.argv" 2>/dev/null     && fail "права симлинка всё-таки сузили"
  pass
  teardown
}

t_legacy_setup_script_refuses_to_run() {
  setup "устаревший setup-monitoring.sh отказывается работать и не трогает crontab"
  printf '0 4 * * * /root/monitoring/offsite-backup.sh
' > "$TMP/crontab"
  cp "$REAL/setup-monitoring.sh" "$SRC/setup-monitoring.sh"
  PATH="$STUBS:$PATH" bash "$SRC/setup-monitoring.sh" > "$TMP/out" 2>&1
  RC=$?
  [ "$RC" = "0" ] && fail "устаревший установщик отработал с нулём"
  assert_out_contains "install-portfolio-monitor.sh" || { teardown; return; }
  grep -q "offsite-backup.sh" "$TMP/crontab" || fail "crontab тронут устаревшим установщиком — <<$(cat "$TMP/crontab")>>"
  [ -f "$TMP/crontab.writes" ] && fail "устаревший установщик писал crontab"
  pass
  teardown
}

# ------------------------------------------------------------------ run ---

echo "Тесты установщика опрашивалки"
for t in $(declare -F | awk '{print $3}' | grep '^t_'); do "$t"; done

echo
printf 'Пройдено: %d, провалено: %d, пропущено: %d\n' "$PASSED" "$FAILED" "$SKIPPED"

DECLARED=$(declare -F | awk '{print $3}' | grep -c '^t_')
if [ $((PASSED + FAILED + SKIPPED)) -lt "$DECLARED" ]; then
  printf 'ОШИБКА: объявлено сценариев %d, отчиталось %d — какой-то не запустился\n' "$DECLARED" "$((PASSED + FAILED + SKIPPED))"
  exit 1
fi

[ "$FAILED" -eq 0 ] || exit 1
