#!/usr/bin/env bash
#
# Установка портфельной опрашивалки. Идемпотентен: гоняется на каждом деплое,
# повторный прогон ничего не ломает и не плодит вторых строк в crontab.
#
#   bash monitoring/install-portfolio-monitor.sh external   # стейдж
#   bash monitoring/install-portfolio-monitor.sh peer       # прод
#
# Зачем установщиком, а не руками: рабочая копия на стейдже до сих пор лежала
# положенной руками и с репозиторием совпадала по счастью, а не по механизму.
# Строка reload'а certbot существовала только в root-crontab прода и исчезла бы
# при пересборке хоста бесшумно. Оба случая — тихий отказ по устройству.
#
# Откуда cron запускает скрипты — зависит от того, КОМУ ПРИНАДЛЕЖИТ рабочая копия,
# и на двух машинах ответ разный:
#
#   стейдж — /root/todo принадлежит root, деплой ходит туда root'ом. Запускаем
#            прямо из репозитория: копия в стороне замёрзла бы на дне установки
#            и разошлась бы с git (ровно это уже случилось со server-monitor.sh,
#            offsite-backup.sh и backup.sh);
#   прод   — /home/deploy/todo пишут непривилегированный deploy и CI. Root-cron,
#            исполняющий скрипт из такого каталога, — это root каждые пять минут
#            для всякого, кто может туда писать. Поэтому на проде ставится
#            root-owned копия в /root/monitoring. Честно: пока deploy состоит в
#            группе docker, он и так фактически root, и копия этого пути не
#            закрывает — она лишь не добавляет ещё один к моменту, когда группу
#            у deploy уберут.
#
# Плата за копию — расхождение с git после каждого прод-деплоя, меняющего эти
# скрипты. Оно не тихое: опрашивалка на проде сверяет копию с репозиторием
# (COPY_DRIFT_CHECK) и сообщает, что установщик пора перезапустить.
#
# Свои строки crontab установщик метит хвостом « # portfolio-monitor:managed» и
# снимает только их и прежние строки, которые заменяет, — точным текстом.
#
# Выключатель — файл /root/monitoring/portfolio-monitor.disabled. Пока он лежит,
# опрашивалка при запуске сразу выходит, а установщик снимает её строку из crontab
# и не возвращает её — иначе выключенную руками опрашивалку вернул бы первый же
# деплой стейджа. Продление сертификатов выключатель не трогает: старая строка
# reload'а уже снята, и без certbot-renew.sh nginx перестал бы перечитывать
# продлённые сертификаты.

set -uo pipefail

ROLE="${1:-}"
case "$ROLE" in
  external|peer) ;;
  *) echo "usage: $0 <external|peer>" >&2; exit 2 ;;
esac

if [ "$(id -u)" != "0" ]; then
  echo "нужен root: ставим cron, logrotate и /var/lib" >&2
  exit 2
fi

SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Каталоги переопределяются только тестами установщика. Префикс PM_ — чтобы общие
# имена вроде STATE_DIR из окружения root не просочились в установку.
CONF_DIR="${PM_CONF_DIR:-/root/monitoring}"
STATE_DIR="${PM_STATE_DIR:-/var/lib/portfolio-monitor}"
LOGROTATE_DIR="${PM_LOGROTATE_DIR:-/etc/logrotate.d}"
CONF="${CONF_DIR}/portfolio-monitor.conf"
DISABLED_FLAG="${CONF_DIR}/portfolio-monitor.disabled"

# Молча зелёный деплой при неустановленной опрашивалке — тот же тихий отказ,
# который она чинит. Поэтому каждая запись проверяется, а не предполагается.
die() { echo "ОШИБКА: $1 — установка прервана" >&2; exit 1; }


# CRLF чиним ДО тестов и по всем исполняемым файлам, включая заглушки без
# расширения: файл с \r в shebang не запустится, и тесты упали бы на этом, а не
# на своей сути. Маска *.sh одна такие файлы не покрывает.
sed -i 's/\r$//' "${SRC}"/*.sh "${SRC}"/tests/*.sh "${SRC}"/tests/stubs*/* 2>/dev/null || true
chmod +x "${SRC}"/*.sh "${SRC}"/tests/*.sh "${SRC}"/tests/stubs*/* 2>/dev/null || true

# Выключатель проверяется ДО тестов: если опрашивалку выключают из-за сломанного
# кода, красные тесты не должны мешать снять её строку из cron.
DISABLED=0
if [ -f "$DISABLED_FLAG" ]; then
  DISABLED=1
  echo "--- опрашивалка выключена (${DISABLED_FLAG} от $(date -r "$DISABLED_FLAG" '+%d.%m.%Y %H:%M')): строку cron снимаю и не ставлю ---"
fi

# STATE_DIR берём из готового конфига хоста: опрашивалка ищет отметку продления
# именно там, и совпадение путей не должно держаться на совпадении умолчаний. Читаем
# в подоболочке со СНЯТЫМ STATE_DIR — иначе значение просочилось бы из родителя, и
# «в конфиге ключа нет» стало бы неотличимо от «конфиг не разобрался».
# Блок стоит ПОСЛЕ выключателя и при нём не роняет установку: снять строку сломанной
# опрашивалки важнее придирки к конфигу — тот же довод, что и у тест-гейта ниже.
if [ -f "$CONF" ]; then
  CONF_PROBLEM=""
  # Разбор и ИСПОЛНЕНИЕ — разные вещи. Точка возвращает код последней команды файла,
  # и законный конфиг, кончающийся на `[ -f … ] && . …` с отсутствующим файлом, по
  # коду возврата неотличим от сломанного: установщик падал бы, а вместе с ним и
  # джоба deploy-staging на каждом мерже в master.
  if ! CONF_SYNTAX="$(bash -n "$CONF" 2>&1)"; then
    CONF_PROBLEM="конфиг ${CONF} не разобрался (${CONF_SYNTAX}) — опрашивалка прочтёт его так же"
  else
    # Маркер OK в конце: `exit` или падение внутри конфига обрывают подоболочку
    # молча, и пустое значение стало бы неотличимо от «ключа в конфиге нет».
    # Опрашивалка сорсит этот же файл в ГЛАВНОЙ оболочке — там такой обрыв
    # завершает её саму с кодом 0, то есть ровно тем тихим отказом, против
    # которого она и заведена.
    # Значение, режим оболочки и маркер уходят ОТДЕЛЬНЫМ дескриптором: `trap … EXIT`
    # или любой вывод конфига в stdout после возврата иначе подмешался бы в конец и
    # прочитался как обрыв — то есть ронял бы установку на исправном конфиге.
    # Перенаправление делаем через exec, а НЕ на группе команд: ловушка EXIT
    # срабатывает уже после группы, когда её перенаправления сняты, и снова писала бы
    # в перехватываемый канал. exec держит stdout закрытым всю жизнь подоболочки.
    CONF_OUT="$( exec 3>&1 >/dev/null 2>&1; unset STATE_DIR; . "$CONF"; printf '%s\n%s\nOK' "${STATE_DIR:-}" "$-" >&3 )"
    CONF_REST="${CONF_OUT%$'\n'OK}"
    CONF_FLAGS="${CONF_REST##*$'\n'}"
    CONF_VALUE="${CONF_REST%$'\n'*}"
    if [ "${CONF_OUT##*$'\n'}" != "OK" ]; then
      CONF_PROBLEM="конфиг ${CONF} оборвался на полуслове (exit или ошибка внутри) — опрашивалка оборвётся на том же месте, не доделав ни одной проверки"
    elif case "$CONF_FLAGS" in *e*) case "$-" in *e*) false ;; *) true ;; esac ;; *) false ;; esac; then
      # Опрашивалка намеренно написана без -e: она вся построена на ненулевых кодах
      # (промахи grep, [ … ], таймауты). Унаследовав его из конфига, она умрёт на
      # первой штатной неудаче — молча, с пустым логом и без маячка.
      CONF_PROBLEM="конфиг ${CONF} меняет режим оболочки (set -e) — опрашивалка сорсит его в главной оболочке и умрёт на первой штатной неудаче"
    else
      case "$CONF_VALUE" in
        "") ;;
        *[[:space:]]*) CONF_PROBLEM="STATE_DIR в ${CONF} содержит пробелы — строка cron из такого пути сломается" ;;
        # В поле команды crontab процент означает перевод строки: путь с ним обрежет
        # строку молча, тише и злее, чем пробел.
        *%*) CONF_PROBLEM="STATE_DIR в ${CONF} содержит % — в поле команды crontab это перевод строки, и строка оборвётся на нём" ;;
        /*) STATE_DIR="$CONF_VALUE" ;;
        *) CONF_PROBLEM="STATE_DIR в ${CONF} не абсолютный (${CONF_VALUE}) — опрашивалка и продление разойдутся по разным каталогам" ;;
      esac
    fi
  fi
  if [ -n "$CONF_PROBLEM" ]; then
    [ "$DISABLED" = "0" ] && die "$CONF_PROBLEM"
    echo "ВНИМАНИЕ: ${CONF_PROBLEM}. Опрашивалка выключена — её строку снимаю, а каталог состояния беру по умолчанию: ${STATE_DIR}" >&2
    # Выключатель снимает строку опрашивалки, но НЕ строку продления: на проде
    # установщик всё равно решает, где ей искать отметку, и обязан это назвать.
    [ "$ROLE" = "peer" ] && echo "          Строка продления при этом СТАВИТСЯ и будет искать отметку в ${STATE_DIR}; после починки конфига перезапустите установщик." >&2
  fi
fi

SUITES=""
[ "$DISABLED" = "0" ] && SUITES="portfolio-monitor"
[ "$ROLE" = "peer" ] && SUITES="${SUITES} certbot-renew"
for suite in $SUITES; do
  echo "=== Тесты ${suite} (до установки) ==="
  bash "${SRC}/tests/${suite}.test.sh" || die "тесты ${suite} не прошли"
done

mkdir -p "$CONF_DIR" "$STATE_DIR" || die "не создал ${CONF_DIR} или ${STATE_DIR}"
chmod 750 "$STATE_DIR"

install -m 0644 "${SRC}/portfolio-monitor.logrotate" "${LOGROTATE_DIR}/portfolio-monitor" || die "не положил logrotate опрашивалки"

# Каталог, из которого cron будет запускать скрипты (см. шапку).
if [ "$ROLE" = "peer" ]; then
  RUN_DIR="$CONF_DIR"
  install -m 0755 -o root -g root "${SRC}/portfolio-monitor.sh" "${RUN_DIR}/portfolio-monitor.sh" || die "не положил копию portfolio-monitor.sh"
  install -m 0755 -o root -g root "${SRC}/certbot-renew.sh"     "${RUN_DIR}/certbot-renew.sh"     || die "не положил копию certbot-renew.sh"
  install -m 0644 "${SRC}/certbot-renew.logrotate" "${LOGROTATE_DIR}/certbot-renew" || die "не положил logrotate продления"
  # Отсчёт для проверки «certbot-renew давно не отрабатывал» — с установки: иначе
  # опрашивалка подняла бы тревогу раньше первого прогона по cron.
  [ -f "${STATE_DIR}/certbot-renew.ok" ] || date +%s > "${STATE_DIR}/certbot-renew.ok" || die "не записал отметку продления"
else
  RUN_DIR="$SRC"
fi

# Конфиг НИКОГДА не перезаписываем. В него пишутся только значения, свои у хоста:
# пороги, список сертов для сверки и путь отметки продления — значения по умолчанию
# в скрипте (последние два — по роли), иначе на хост с готовым конфигом новые
# значения не приезжали бы никогда.
if [ ! -f "$CONF" ]; then
  echo "--- создаю ${CONF} (профиль ${ROLE}) ---"
  {
    echo "ROLE=${ROLE}"
    echo "VK_CONF_FILE=${CONF_DIR}/monitor.conf"
    if [ "$ROLE" = "external" ]; then
      echo "TARGETS_FILE=${SRC}/portfolio-targets.conf"
      echo "PEER_TRANSPORT=ssh"
      echo "PEER_LABEL=прод"
      echo "PEER_HOST=${PM_PEER_HOST:-82.114.226.107}"
      echo "PEER_SSH_KEY=/root/.ssh/id_ed25519_backup"
    else
      echo "PEER_TRANSPORT=local"
      echo "PEER_LABEL=стейдж"
      # Root-owned копия обновляется только ручной установкой — сверяем её
      # с репозиторием, чтобы расхождение было видно, а не копилось молча.
      echo "COPY_DRIFT_CHECK=\"${RUN_DIR}:${SRC}\""
    fi
  } > "${CONF}.tmp" && chmod 600 "${CONF}.tmp" && mv "${CONF}.tmp" "$CONF" || die "не записал ${CONF}"
else
  echo "--- ${CONF} уже есть, не трогаю ---"
fi

# Учётка VK: без неё опрашивалка не сможет отправить ни одного алерта, и сторож
# сам станет тихим отказом. Проверяем существование, а не гадаем по пути.
# Читаем сорсингом, а не grep'ом: второй парсер того же файла спотыкается на
# кавычках и на `export`, а ложная тревога сторожевого скрипта на каждом деплое
# приучает не читать его предупреждения вовсе. Синтаксис конфига проверен выше.
VK_CONF="$( unset STATE_DIR VK_CONF_FILE; . "$CONF" >/dev/null 2>&1; printf '%s' "${VK_CONF_FILE:-}" )"
if [ ! -r "$VK_CONF" ] || ! grep -q '^VK_TOKEN=' "$VK_CONF" 2>/dev/null; then
  echo "ВНИМАНИЕ: ${VK_CONF} не читается или без VK_TOKEN — алерты уходить не будут." >&2
  echo "          Опрашивалка это увидит сама (проверка «Опрашивалка») и напишет в лог," >&2
  echo "          но сообщить владельцу сможет только соседняя сторона." >&2
fi

TAG="# portfolio-monitor:managed"
# timeout 280 — потолок меньше окна cron. Зависший прогон иначе держал бы flock
# и отбивал все следующие запуски навсегда: опрашивалка выключилась бы молча.
# Путь абсолютный: строка PATH= выше в чужой таблице не должна его терять.
CRON_MON=""
# Потолок пишем ОДНИМ числом и в `timeout`, и в окружение прогона: опрашивалка сверяет
# с ним свой бюджет, и разойтись этим двум значениям физически нечем. Из конфига она
# потолок не берёт — иначе конфиг отключал бы проверку, которая его же и сторожит.
CRON_TIMEOUT=280
[ "$DISABLED" = "0" ] && CRON_MON="*/5 * * * * CRON_TIMEOUT_SEC=${CRON_TIMEOUT} /usr/bin/timeout ${CRON_TIMEOUT} ${RUN_DIR}/portfolio-monitor.sh >> /var/log/portfolio-monitor.log 2>&1 ${TAG}"
CRON_CERT=""
# Смещение от начала часа — чтобы не толкаться с остальными задачами. Путь отметки
# передаётся явно: опрашивалка ищет её в своём STATE_DIR, и совпадение путей не
# должно держаться на совпадении значений по умолчанию.
[ "$ROLE" = "peer" ] && CRON_CERT="17 * * * * RENEW_OK_FILE=${STATE_DIR}/certbot-renew.ok ${RUN_DIR}/certbot-renew.sh >> /var/log/certbot-renew.log 2>&1 ${TAG}"

# Строка cron зовёт скрипт напрямую: без бита исполнения cron падает с «Permission
# denied», а лог этого никто не читает — так внутренний монитор прода однажды молча
# простоял ~26 часов. Проверяем только те скрипты, чьи строки сейчас ставятся: иначе
# при выключателе проверка мешала бы снять строку сломанной опрашивалки.
[ -z "$CRON_MON" ]  || [ -x "${RUN_DIR}/portfolio-monitor.sh" ] || die "${RUN_DIR}/portfolio-monitor.sh не исполняемый"
[ -z "$CRON_CERT" ] || [ -x "${RUN_DIR}/certbot-renew.sh" ]     || die "${RUN_DIR}/certbot-renew.sh не исполняемый"

# Прежние строки, которые заменяет эта установка, — как они стоят на хостах
# (сверено 15.09.2026); пробелы и табы при сравнении схлопываются.
LEGACY_MON='*/5 * * * * /root/monitoring/external-monitor.sh'
LEGACY_CERT='0 */12 * * * docker exec certbot certbot renew --quiet && docker exec nginx-proxy nginx -s reload'

# crontab -l возвращает 1 и когда crontab пуст, и когда прочитать его не удалось
# (права, обновление пакета cron, SELinux). Если не различить, второй случай
# затирает ВЕСЬ crontab: на стейдже вместе с ним уехал бы offsite-бэкап, на проде —
# replyai-probe, server-monitor и backup. Молча.
EXISTING="$(crontab -l 2>/dev/null)"
CRON_RC=$?
if [ "$CRON_RC" -ne 0 ]; then
  # Текст ошибки разбираем без конвейера: под pipefail код `crontab -l | grep` —
  # это код самого crontab, то есть 1 и при пустой таблице, и установка на хосте
  # без crontab прерывалась бы как при нечитаемой таблице.
  CRON_ERR="$(crontab -l 2>&1 >/dev/null)"
  case "${CRON_ERR,,}" in
    *"no crontab"*) EXISTING="" ;;
    *) die "crontab -l вернул ошибку (${CRON_RC}): ${CRON_ERR}" ;;
  esac
fi

# Снимаем только своё: незакомментированные строки с меткой в конце и прежние строки,
# которые эта установка заменяет, — только вместе с установкой замены. Шаблон по
# подстроке снимал бы и чужие строки с похожими именами (на проде общий certbot и
# пробы других проектов), а старую external-monitor.sh снимал бы и при выключенной
# опрашивалке — откат по README оставлял бы стейдж вообще без наблюдения.
# $1: keep — строки, которые остаются; drop — которые снимаются; commented —
# закомментированные строки опрашивалки (их не трогаем, но говорим о них).
filter_crontab() {
  printf '%s\n' "$EXISTING" | awk -v mode="$1" -v tag=" ${TAG}" \
      -v mon="${CRON_MON:+$LEGACY_MON}" -v cert="${CRON_CERT:+$LEGACY_CERT}" '
    { line = $0; sub(/[ \t\r]+$/, "", line)
      norm = line; gsub(/[ \t]+/, " ", norm)
      tagged = length(line) >= length(tag) && substr(line, length(line) - length(tag) + 1) == tag
      commented = line ~ /^[ \t]*#/
      managed = (tagged && !commented) || (mon != "" && norm == mon) || (cert != "" && norm == cert) }
    (mode == "keep" && !managed) || (mode == "drop" && managed) || (mode == "commented" && tagged && commented) { print }'
}

KEPT="$(filter_crontab keep)"
DESIRED="$(
  [ -n "$KEPT" ] && printf '%s\n' "$KEPT"
  [ -n "$CRON_MON" ] && printf '%s\n' "$CRON_MON"
  [ -n "$CRON_CERT" ] && printf '%s\n' "$CRON_CERT"
)"

# Строку выключают флагом, а не комментарием: закомментированную установщик не
# трогает. Текст собираем по факту: какая это строка и ставится ли замена.
COMMENTED="$(filter_crontab commented)"
if [ -n "$COMMENTED" ]; then
  printf '%s
' "$COMMENTED" | while IFS= read -r line; do
    case "$line" in
      *certbot-renew.sh*) what="строка продления сертификатов" ;;
      *) what="строка опрашивалки" ;;
    esac
    if [ -n "$CRON_MON" ] || [ -n "$CRON_CERT" ]; then
      echo "ВНИМАНИЕ: в crontab закомментирована ${what} — установщик её не трогает и ставит свою рядом. Выключать надо файлом ${DISABLED_FLAG}: ${line}" >&2
    else
      echo "ВНИМАНИЕ: в crontab закомментирована ${what} — установщик её не трогает. Выключать надо файлом ${DISABLED_FLAG}: ${line}" >&2
    fi
  done
fi

# Метка засчитывается только в конце строки. Строка с меткой, после которой что-то
# дописано, останется как чужая, а рядом встанет вторая — молча задвоенная
# опрашивалка. Снимать такую нельзя (так же выглядит чужая строка), поэтому говорим.
printf '%s
' "$KEPT" | grep -v '^[[:space:]]*#' | grep -F "$TAG" | while IFS= read -r line; do
  echo "ВНИМАНИЕ: строка с меткой опрашивалки не в каноничной форме (метка должна быть в конце строки) — проверь руками, иначе рядом встанет вторая: ${line}" >&2
done

# Старая строка, не совпавшая текстом, продолжит работать рядом с новой — вреда нет,
# но работа задвоится. Говорим об этом, а не молчим.
if [ -n "$CRON_MON" ]; then
  case "$KEPT" in *"/root/monitoring/external-monitor.sh"*)
    echo "ВНИМАНИЕ: в crontab осталась строка с /root/monitoring/external-monitor.sh, не совпавшая с ожидаемой текстом — проверь руками" >&2 ;;
  esac
fi
if [ -n "$CRON_CERT" ]; then
  case "$KEPT" in *"certbot renew --quiet && docker exec nginx-proxy"*)
    echo "ВНИМАНИЕ: в crontab осталась строка reload'а certbot, не совпавшая с ожидаемой текстом — проверь руками" >&2 ;;
  esac
fi

# Таблица уже в нужном виде — не трогаем её и не плодим бэкапов: самый ранний бэкап
# тогда и есть таблица до первой установки.
if [ "$DESIRED" = "$EXISTING" ]; then
  echo "--- crontab уже в нужном виде, не трогаю ---"
else
  if [ -n "$EXISTING" ]; then
    BACKUP="${CONF_DIR}/crontab.bak.$(date +%Y%m%d-%H%M%S)"
    ( umask 077; printf '%s\n' "$EXISTING" > "$BACKUP" ) || die "не записал бэкап crontab ${BACKUP}"
    echo "--- бэкап crontab: ${BACKUP} ---"
  fi
  # Сравниваем так же, как фильтр: по схлопнутым пробелам. Посимвольное сравнение
  # объявило бы «снятой» свою же строку, отличающуюся лишним пробелом, — а она тут
  # же ставится обратно.
  CRON_MON_NORM="$(printf '%s' "$CRON_MON" | tr -s '[:space:]' ' ')"
  CRON_CERT_NORM="$(printf '%s' "$CRON_CERT" | tr -s '[:space:]' ' ')"
  filter_crontab drop | while IFS= read -r line; do
    [ -n "$line" ] || continue
    line_norm="$(printf '%s' "$line" | tr -s '[:space:]' ' ')"
    [ -n "$CRON_MON_NORM" ] && [ "$line_norm" = "$CRON_MON_NORM" ] && continue
    [ -n "$CRON_CERT_NORM" ] && [ "$line_norm" = "$CRON_CERT_NORM" ] && continue
    echo "  снимаю строку crontab: ${line}"
  done
  printf '%s\n' "$DESIRED" | crontab - || die "crontab отверг новую таблицу, прежняя осталась на месте"
  # Сверяем записанное, а не только код возврата.
  [ "$(crontab -l 2>/dev/null)" = "$DESIRED" ] || die "crontab после записи не совпал с ожидаемым"
fi

echo "=== Установлено (${ROLE}) ==="
crontab -l | grep -F "$TAG" || true
echo
echo "Проверить руками: ${RUN_DIR}/portfolio-monitor.sh; echo rc=\$?"
echo "Лог:              tail -20 /var/log/portfolio-monitor.log"
