#!/usr/bin/env bash
#
# Портфельная опрашивалка keepware.
#
# Заменяет external-monitor.sh: та смотрела на один домен из семи, держала
# состояние в /tmp, повторяла один и тот же алерт каждые 30 минут и не имела
# сторожа над собой. Разбор — response_monitoring_prober_survey_2026-08-21.md.
#
# Что делает:
#   1. взаимный дед-мэн стейдж <-> прод: каждый прогон оставляет маячок, каждый
#      прогон читает маячок другой стороны. Молчание дольше порога = сигнал.
#      Это закрывает главную дырку: раньше смерть опрашивалки была неотличима
#      от «всё хорошо», потому что и то и другое выглядит как тишина в чате;
#   2. цели списком из файла, TLS каждой берётся ИЗ РУКОПОЖАТИЯ, а не с диска
#      (ровно тот класс отказа, что убил бота replyAI 14.08.2026);
#   3. по желанию — сверка «серт в эфире против серта на диске» там, где есть
#      доступ к хосту: прямой детектор «nginx не перечитал продлённый серт»;
#   4. дисциплина шума: сигнал на СМЕНУ состояния, сообщение о восстановлении,
#      все отказы прогона одним сообщением, состояние вне /tmp, лог с историей.
#
# Роль хоста задаётся конфигом, скрипт один на обе машины. См. README.md.

set -uo pipefail

CONF_FILE="${PORTFOLIO_MONITOR_CONF:-/root/monitoring/portfolio-monitor.conf}"
if [ -r "$CONF_FILE" ]; then
  # shellcheck disable=SC1090
  . "$CONF_FILE"
fi

: "${STATE_DIR:=/var/lib/portfolio-monitor}"
: "${LOG_FILE:=/var/log/portfolio-monitor.log}"
: "${LOCK_FILE:=/run/portfolio-monitor.lock}"
: "${VK_CONF_FILE:=}"
: "${TARGETS_FILE:=}"
# Роль хоста нужна явно: без неё «TARGETS_FILE потерялся из конфига» неотличимо
# от «целей и не должно быть», и внешняя опрашивалка превращается в вечный
# зелёный no-op с исправным маячком, которого дед-мэн по устройству не поймает.
: "${ROLE:=}"
: "${CERT_WARN_DAYS:=21}"
: "${SLOW_MS:=10000}"
: "${HTTP_TIMEOUT:=20}"
# 8 с, а не 15: девять целей × две попытки × 15 с не помещаются в окно cron,
# и маячок начинал бы отставать настолько, что пир поднимал бы ложную тревогу.
: "${TLS_TIMEOUT:=8}"
: "${RETRY_DELAY:=10}"
: "${HEARTBEAT_HOUR:=6}"
: "${ALERT_PREFIX:=[portfolio]}"
: "${SITE:=$(hostname -s 2>/dev/null || echo unknown)}"
: "${VK_API_VERSION:=5.199}"
# Дед-мэн: none — выключен, local — маячок пира лежит рядом (его кладёт пир),
# ssh — сами и читаем маячок пира, и доставляем ему свой.
: "${PEER_TRANSPORT:=none}"
: "${PEER_LABEL:=пир}"
: "${PEER_HOST:=}"
: "${PEER_SSH_USER:=root}"
: "${PEER_SSH_KEY:=}"
: "${PEER_STATE_DIR:=/var/lib/portfolio-monitor}"
: "${PEER_SILENCE_SEC:=1800}"
# Маячок из будущего означает разъехавшиеся часы. Без этой проверки часы,
# ушедшие вперёд на N секунд, делают мёртвого пира «живым» ещё на N секунд:
# возраст выходит отрицательным и порог молчания не срабатывает никогда.
: "${PEER_CLOCK_TOLERANCE_SEC:=300}"
: "${PEER_SSH_TIMEOUT:=30}"
: "${CERT_DRIFT_DOMAINS:=}"
# "установленная_копия:копия_в_репозитории" — там, где cron исполняет не сам
# репозиторий, а root-owned копию, копия обновляется только ручной установкой.
# Без этой проверки расхождение накапливается молча — ровно то, что уже
# произошло на обеих машинах со server-monitor.sh и соседями.
: "${COPY_DRIFT_CHECK:=}"
: "${CERT_DRIFT_TOLERANCE_SEC:=3600}"
: "${CERTBOT_CONTAINER:=certbot}"
: "${CERT_LIVE_DIR:=/etc/letsencrypt/live}"

# Учётка VK — общая шина алертов портфеля. Держим ОДНУ копию токена на хосте:
# по умолчанию подтягиваем её из конфига соседнего монитора, а не дублируем.
if [ -n "$VK_CONF_FILE" ] && [ -r "$VK_CONF_FILE" ]; then
  # shellcheck disable=SC1090
  . "$VK_CONF_FILE"
fi

mkdir -p "$STATE_DIR" 2>/dev/null
mkdir -p "$(dirname "$LOG_FILE")" 2>/dev/null

# Временные файлы сообщений не должны копиться в STATE_DIR при аварийном выходе.
cleanup_tmp() { rm -f "${STATE_DIR}"/msg.?????? 2>/dev/null; }
trap cleanup_tmp EXIT

# Токен VK не должен попасть ни в лог, ни в текст сообщения: curl умеет вернуть
# переданный ему URL в тексте ошибки.
redact() {
  local s="$1"
  [ -n "${VK_TOKEN:-}" ] && s="${s//${VK_TOKEN}/<VK_TOKEN>}"
  printf '%s' "$s"
}

log() { printf '%s %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$(redact "$1")" >> "$LOG_FILE"; }

# Один прогон за раз: сеть может тормозить, а cron приходит каждые 5 минут.
# Зависший прогон не обновит маячок — и пир об этом сообщит, как и задумано.
if command -v flock >/dev/null 2>&1; then
  exec 9>"$LOCK_FILE"
  if ! flock -n 9; then
    log "SKIP предыдущий прогон ещё не закончился"
    exit 0
  fi
fi

# ------------------------------------------------------------- хелперы ------

# Значение для конфига curl берётся в кавычки — экранируем слэш и кавычку.
cfg_escape() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  printf '%s' "$s"
}

# "12.500" -> 12500. bc намеренно не зовём: лишняя зависимость ради умножения.
ms_from_seconds() {
  local s="${1:-0}" int frac
  s="${s//,/.}"
  # Нечисловой ввод не должен ронять арифметику: `$((10#abc))` пишет ошибку и
  # отдаёт пустую строку, а пустая строка тихо отключает проверку времени.
  case "$s" in ''|*[!0-9.]*) printf '0'; return ;; esac
  int="${s%%.*}"
  if [ "$s" = "$int" ]; then frac="000"; else frac="${s#*.}"; fi
  frac="${frac}000"
  frac="${frac:0:3}"
  printf '%s' "$(( 10#${int:-0} * 1000 + 10#${frac} ))"
}

# Кладёт код в HTTP_CODE, время в HTTP_TIME. Тело намеренно не разбирается:
# критерий «здорового ответа» для чужих проектов пока не согласован, а гадать
# по телу — верный способ получить стену ложных срабатываний.
# URL уходит конфигом на stdin (-K -), а не аргументом: argv на хосте виден всем
# (/proc без hidepid), и локальные пользователи прочитали бы токен обычным ps.
http_get() {
  local raw last
  raw="$(printf 'url = "%s"\n' "$(cfg_escape "$1")" \
         | curl -sS -m "$HTTP_TIMEOUT" -w $'\n%{http_code} %{time_total}' -K - 2>/dev/null)"
  last="${raw##*$'\n'}"
  HTTP_CODE="${last%% *}"
  HTTP_TIME="${last#* }"
}

# notAfter/notBefore сертификата, который хост ПРЕДЪЯВЛЯЕТ В ЭФИРЕ.
wire_cert_date() {
  local host="$1" what="$2"
  echo | timeout "$TLS_TIMEOUT" openssl s_client -connect "${host}:443" -servername "$host" 2>/dev/null \
    | openssl x509 -noout "$what" 2>/dev/null | cut -d= -f2
}

# timeout снаружи обязателен: ConnectTimeout покрывает только установку TCP.
# Если sshd принял соединение и завис (пир под нагрузкой, забитый диск), ssh
# висит бесконечно, прогон не заканчивается и flock отбивает ВСЕ следующие
# запуски cron навсегда — опрашивалка выключается молча, с кодом успеха.
ssh_peer() {
  timeout "$PEER_SSH_TIMEOUT" \
    ssh -i "$PEER_SSH_KEY" -o BatchMode=yes -o ConnectTimeout=10 \
        -o ServerAliveInterval=5 -o ServerAliveCountMax=3 \
        -o StrictHostKeyChecking=accept-new -o LogLevel=ERROR \
        "${PEER_SSH_USER}@${PEER_HOST}" "$1" 2>/dev/null
}

# ------------------------------------------------------------- проверки -----
# Каждая печатает человеческую строку (на успехе — деталь для пульса,
# на отказе — причину) и возвращает 0 / 1.

check_tls() {
  local host="$1" enddate exp now days
  enddate="$(wire_cert_date "$host" -enddate)"
  if [ -z "$enddate" ]; then
    echo "TLS-рукопожатие с ${host} не отдало сертификат (хост недоступен или серт битый)"
    return 1
  fi
  exp="$(date -d "$enddate" +%s 2>/dev/null)"
  if [ -z "$exp" ]; then
    echo "не разобрал срок сертификата ${host}: ${enddate}"
    return 1
  fi
  now="$(date +%s)"
  days=$(( (exp - now) / 86400 ))
  if [ "$days" -lt "$CERT_WARN_DAYS" ]; then
    # Let's Encrypt продлевает за 30 дней до конца. Если reload отработал, в эфире
    # всегда лежит серт с запасом ~90 дней. Меньше порога в эфире означает, что
    # продление либо не идёт, либо идёт, а nginx его не подхватывает.
    echo "серт ${host} в эфире живёт ещё ${days} дн. (порог ${CERT_WARN_DAYS})"
    return 1
  fi
  echo "${host}: серт ${days} дн."
  return 0
}

check_http() {
  local host="$1" path="$2" expect="$3" problems="" ms
  http_get "https://${host}${path}"
  if [ "$HTTP_CODE" != "$expect" ]; then
    problems="HTTP ${HTTP_CODE} (ждали ${expect})"
    [ "$HTTP_CODE" = "000" ] && problems="${problems} — соединение не встало"
  fi
  ms="$(ms_from_seconds "$HTTP_TIME")"
  if [ "$ms" -gt "$SLOW_MS" ] 2>/dev/null; then
    # Одна сетевая яма даёт и не-200, и превышение времени. Обе причины живут
    # в ОДНОЙ проверке, поэтому владелец получит одну строку, а не две.
    [ -n "$problems" ] && problems="${problems}, "
    problems="${problems}ответ за ${HTTP_TIME}с (порог $((SLOW_MS / 1000))с)"
  fi
  if [ -n "$problems" ]; then
    echo "${host}${path}: ${problems}"
    return 1
  fi
  echo "${host}${path}: ${HTTP_CODE} за ${HTTP_TIME}с"
  return 0
}

# Прямой детектор инцидента replyAI: продление прошло, nginx держит в памяти
# старый серт. Ловится в тот же день, а не через недели, но требует доступа
# к хосту — поэтому включается только там, где серты лежат локально.
check_cert_drift() {
  local host="$1" wire disk wire_epoch disk_epoch delta
  wire="$(wire_cert_date "$host" -startdate)"
  if [ -z "$wire" ]; then
    echo "${host}: не смог снять сертификат из эфира"
    return 1
  fi
  disk="$(docker exec "$CERTBOT_CONTAINER" openssl x509 -noout -startdate \
          -in "${CERT_LIVE_DIR}/${host}/cert.pem" 2>/dev/null | cut -d= -f2)"
  if [ -z "$disk" ]; then
    echo "${host}: не смог прочитать сертификат с диска (${CERTBOT_CONTAINER}:${CERT_LIVE_DIR}/${host}/cert.pem)"
    return 1
  fi
  wire_epoch="$(date -d "$wire" +%s 2>/dev/null)"
  disk_epoch="$(date -d "$disk" +%s 2>/dev/null)"
  if [ -z "$wire_epoch" ] || [ -z "$disk_epoch" ]; then
    echo "${host}: не разобрал даты сертификата (эфир «${wire}», диск «${disk}»)"
    return 1
  fi
  delta=$(( disk_epoch - wire_epoch ))
  if [ "$delta" -gt "$CERT_DRIFT_TOLERANCE_SEC" ]; then
    echo "${host}: на диске серт от $(date -u -d "@${disk_epoch}" '+%d.%m'), в эфире от $(date -u -d "@${wire_epoch}" '+%d.%m') — nginx не перечитал продлённый серт"
    return 1
  fi
  echo "${host}: эфир и диск совпадают"
  return 0
}

# --------------------------------------------------------- дед-мэн ----------

# Отдаёт "<код возврата транспорта>\n<первая строка маячка>". Код нужен, чтобы
# отличать «не смогли подключиться» от «подключились, а маячка нет». Вернуть его
# глобальной переменной нельзя: вызов идёт в командной подстановке, то есть в
# подоболочке, и присваивание до вызывающего не доезжает.
peer_beacon_raw() {
  local out rc
  case "$PEER_TRANSPORT" in
    local) out="$(cat "${STATE_DIR}/peer.beacon" 2>/dev/null)"; rc=$? ;;
    ssh)   out="$(ssh_peer "cat '${PEER_STATE_DIR}/beacon.self'")"; rc=$? ;;
    *)     out=""; rc=1 ;;
  esac
  printf '%s\n%s' "$rc" "$(printf '%s' "$out" | head -1)"
}

# Раскладывает вывод peer_beacon_raw в PEER_RC / PEER_BEACON.
read_peer_beacon() {
  local raw
  raw="$(peer_beacon_raw)"
  PEER_RC="${raw%%$'\n'*}"
  # Пустой маячок: командная подстановка срезала хвостовой перевод строки, и в
  # raw остался один код возврата. Без этой ветки код возврата подставился бы
  # вместо маячка и «маячка нет» превратилось бы в «маячок не разбирается».
  if [ "$raw" = "$PEER_RC" ]; then
    PEER_BEACON=""
  else
    PEER_BEACON="${raw#*$'\n'}"
  fi
}

beacon_field() {
  local beacon="$1" name="$2" rest
  case " $beacon " in
    *" ${name}="*) rest="${beacon#*${name}=}"; printf '%s' "${rest%% *}" ;;
    *) printf '' ;;
  esac
}

PEER_RC=0
PEER_BEACON=""

check_peer_alive() {
  local beacon ts age
  read_peer_beacon
  beacon="$PEER_BEACON"
  if [ -z "$beacon" ]; then
    # Различаем «не смогли подключиться» и «подключились, а файла нет»: иначе
    # протухший ssh-ключ на нашей стороне отправит владельца чинить чужую машину.
    if [ "$PEER_TRANSPORT" = "ssh" ] && [ "$PEER_RC" = "255" ]; then
      echo "не встало ssh-соединение с ${PEER_LABEL}ом (${PEER_SSH_USER}@${PEER_HOST}) — машина недоступна либо не принят наш ключ"
      return 1
    fi
    if [ "$PEER_TRANSPORT" = "ssh" ] && [ "$PEER_RC" = "124" ]; then
      echo "чтение маячка ${PEER_LABEL}а не уложилось в ${PEER_SSH_TIMEOUT} с — соединение встало и висит"
      return 1
    fi
    echo "маячок ${PEER_LABEL}а не читается — там не пишется маячок или сторона молчит"
    return 1
  fi
  ts="$(beacon_field "$beacon" ts)"
  case "$ts" in
    ''|*[!0-9]*)
      echo "маячок ${PEER_LABEL}а не разбирается: «${beacon}»"
      return 1 ;;
  esac
  age=$(( $(date +%s) - ts ))
  if [ "$age" -lt "-${PEER_CLOCK_TOLERANCE_SEC}" ]; then
    echo "маячок ${PEER_LABEL}а помечен будущим на $(( -age / 60 )) мин — часы машин разъехались, дед-мэн в таком виде не работает"
    return 1
  fi
  if [ "$age" -gt "$PEER_SILENCE_SEC" ]; then
    echo "${PEER_LABEL} молчит $((age / 60)) мин (порог $((PEER_SILENCE_SEC / 60)) мин) — там умерла опрашивалка или машина"
    return 1
  fi
  echo "${PEER_LABEL}: маячок $((age / 60)) мин назад"
  return 0
}

# Свой канал алертов проверить со своей же стороны нельзя: если он мёртв, сигнал
# об этом не доедет. Поэтому каждая сторона пишет состояние своего канала в
# маячок, а сообщает о поломке — соседняя, у которой канал жив.
# Зовётся ТОЛЬКО когда check_peer_alive в этом же прогоне отработал успешно —
# см. блок прогона. Иначе смерть пира выглядела бы как «канал восстановился»:
# маячок не читается, проверка возвращает 0, run_check снимает состояние отказа
# и печатает «Восстановлено» о том самом узле, чью смерть только что объявили.
check_peer_channel() {
  local beacon ch fails
  read_peer_beacon
  beacon="$PEER_BEACON"
  ch="$(beacon_field "$beacon" channel)"
  fails="$(beacon_field "$beacon" fails)"
  if [ "$ch" = "fail" ]; then
    echo "у ${PEER_LABEL}а сломан канал алертов — его сообщения до владельца не доходят (проверок в отказе там: ${fails:-?})"
    return 1
  fi
  echo "${PEER_LABEL}: канал ${ch:-неизвестен}, проверок в отказе ${fails:-?}"
  return 0
}

# Расхождение установленной копии с репозиторием. Само по себе оно не отказ, но
# означает, что на машине крутится не тот код, который лежит в git, — и узнать об
# этом иначе можно только случайно.
check_copy_drift() {
  local installed="${COPY_DRIFT_CHECK%%:*}" repo="${COPY_DRIFT_CHECK#*:}" diverged="" f base
  if [ ! -d "$installed" ] || [ ! -d "$repo" ]; then
    echo "COPY_DRIFT_CHECK указывает на несуществующий каталог (${COPY_DRIFT_CHECK})"
    return 1
  fi
  for f in "$installed"/*.sh; do
    [ -f "$f" ] || continue
    base="$(basename "$f")"
    [ -f "${repo}/${base}" ] || continue
    cmp -s "$f" "${repo}/${base}" || diverged="${diverged}${base} "
  done
  if [ -n "$diverged" ]; then
    echo "установленные копии разошлись с репозиторием: ${diverged%% }— на машине крутится не то, что в git"
    return 1
  fi
  echo "копии совпадают с репозиторием"
  return 0
}

# Опрашивалка, которая не может проверить, обязана кричать, а не молчать зелёным.
check_config() {
  local problems=""
  # Роль external без целей — вечный зелёный no-op: проверок ноль, маячок свежий,
  # пир доволен. Единственный способ это поймать — потребовать цели явно.
  if [ "$ROLE" = "external" ] && [ -z "$TARGETS_FILE" ]; then
    problems="${problems}роль external, но TARGETS_FILE не задан — портфель не опрашивается вообще; "
  fi
  if [ -n "$TARGETS_FILE" ]; then
    if [ ! -r "$TARGETS_FILE" ]; then
      problems="${problems}не читается файл целей (${TARGETS_FILE}); "
    elif [ "${#TARGET_HOSTS[@]}" -eq 0 ]; then
      problems="${problems}в файле целей (${TARGETS_FILE}) нет ни одной цели; "
    fi
  fi
  # Нераспознанный вид проверки — это молча непроверяемая цель.
  [ -n "$TARGET_PROBLEMS" ] && problems="${problems}${TARGET_PROBLEMS}"
  # Без записи в STATE_DIR не работают ни дедуп, ни отправка (текст сообщения
  # едет временным файлом оттуда же) — то есть канал алертов мёртв целиком.
  [ -d "$STATE_DIR" ] && [ -w "$STATE_DIR" ] || problems="${problems}STATE_DIR (${STATE_DIR}) недоступен для записи; "
  [ -w "$(dirname "$LOG_FILE")" ] || problems="${problems}каталог лога (${LOG_FILE}) недоступен для записи; "
  [ -n "${VK_TOKEN:-}" ] || problems="${problems}нет токена VK; "
  [ -n "${VK_PEER_ID:-}" ] || problems="${problems}нет адресата VK; "
  [ "$PEER_TRANSPORT" = "ssh" ] && [ -z "$PEER_HOST" ] && problems="${problems}дед-мэн по ssh включён, но PEER_HOST пуст; "
  if [ -n "$problems" ]; then
    echo "опрашивалка сломана: ${problems%; }"
    return 1
  fi
  echo "конфиг на месте, целей: ${#TARGET_HOSTS[@]}"
  return 0
}

# --------------------------------------------------------- механика ---------

declare -a NEW_FAIL_LINES=() RECOVERY_LINES=() PENDING_FAIL=() PENDING_CLEAR=() FAILING=() DETAILS=()
declare -a TARGET_HOSTS=() TARGET_CHECKS=() TARGET_PATHS=() TARGET_EXPECT=()
TARGET_PROBLEMS=""
EXIT_CODE=0
ALREADY_WAITED=0
LAST_RC=0

# Точка, дефис и двоеточие кодируются по-разному, а не схлопываются в «_»:
# иначе цели `a.b` и `a-b` делили бы один файл состояния, и восстановление
# одной гасило бы отказ другой.
state_file() {
  local key="$1"
  key="${key//:/_c_}"
  key="${key//./_d_}"
  key="${key//-/_h_}"
  key="${key//[^a-zA-Z0-9_]/_}"
  printf '%s/fail.%s' "$STATE_DIR" "$key"
}

# Для проверок, которые ретраить бессмысленно (сломанный конфиг за 10 секунд не
# починится). Заодно не даёт им съесть единственную за прогон антифлап-паузу.
run_check_once() {
  local saved="$RETRY_DELAY"
  RETRY_DELAY=0
  run_check "$@"
  RETRY_DELAY="$saved"
}

run_check() {
  local key="$1" name="$2"; shift 2
  local out rc file
  out="$("$@")"; rc=$?
  if [ $rc -ne 0 ]; then
    # Антифлап: одиночная сетевая яма не должна поднимать владельца среди ночи.
    # Ждём один раз за прогон — пауза нужна, чтобы яма прошла, а не на каждую цель:
    # при девяти целях пауза на каждую растянула бы прогон за окно cron.
    if [ "$ALREADY_WAITED" = "0" ] && [ "$RETRY_DELAY" -gt 0 ] 2>/dev/null; then
      sleep "$RETRY_DELAY"
      ALREADY_WAITED=1
    fi
    out="$("$@")"; rc=$?
  fi
  LAST_RC=$rc
  file="$(state_file "$key")"
  if [ $rc -ne 0 ]; then
    EXIT_CODE=1
    FAILING+=("${name}: ${out}")
    if [ -f "$file" ]; then
      log "STILL-FAIL ${key}: ${out}"
    else
      NEW_FAIL_LINES+=("[X] ${name}: ${out}")
      PENDING_FAIL+=("$key")
      log "NEW-FAIL ${key}: ${out}"
    fi
  else
    DETAILS+=("${out}")
    if [ -f "$file" ]; then
      local since dur
      since="$(cat "$file")"
      dur=$(( ( $(date +%s) - since ) / 60 ))
      RECOVERY_LINES+=("[OK] Восстановлено: ${name} (в отказе был ${dur} мин)")
      PENDING_CLEAR+=("$key")
      log "RECOVERED ${key} после ${dur} мин"
    fi
  fi
}

send_vk() {
  local text resp rc msg_file
  text="$(redact "$1")"
  if [ -z "${VK_TOKEN:-}" ] || [ -z "${VK_PEER_ID:-}" ]; then
    log "VK-SEND НЕТ УЧЁТКИ: алерт не ушёл — <<${text}>>"
    return 1
  fi
  # Токен VK уходит конфигом на stdin по той же причине, что и URL в http_get.
  # Текст многострочный, поэтому едет отдельным файлом (0600 от mktemp):
  # в конфиге curl перевод строки внутри значения не живёт.
  msg_file="$(mktemp "${STATE_DIR}/msg.XXXXXX" 2>/dev/null)"
  if [ -z "$msg_file" ]; then
    # STATE_DIR может быть недоступен (диск полон, том не смонтирован). Это само
    # по себе отказ, о котором надо СООБЩИТЬ, — значит канал отправки не должен
    # умирать вместе с ним.
    msg_file="$(mktemp "${TMPDIR:-/tmp}/portfolio-msg.XXXXXX" 2>/dev/null)"
  fi
  if [ -z "$msg_file" ]; then
    log "VK-SEND не смог создать временный файл ни в ${STATE_DIR}, ни в ${TMPDIR:-/tmp} — алерт не ушёл"
    return 1
  fi
  printf '%s' "$text" > "$msg_file"
  resp="$(printf 'url = "https://api.vk.com/method/messages.send"\ndata-urlencode = "access_token=%s"\ndata-urlencode = "peer_id=%s"\ndata-urlencode = "random_id=%s"\ndata-urlencode = "v=%s"\ndata-urlencode = "message@%s"\n' \
      "$(cfg_escape "${VK_TOKEN}")" \
      "$(cfg_escape "${VK_PEER_ID}")" \
      "$RANDOM$RANDOM" \
      "$(cfg_escape "${VK_API_VERSION}")" \
      "$(cfg_escape "$msg_file")" \
      | curl -sS -m 15 -K - 2>/dev/null)"
  rc=$?
  rm -f "$msg_file"
  if [ $rc -ne 0 ]; then
    log "VK-SEND curl упал (rc=${rc}) — алерт не ушёл"
    return 1
  fi
  # Канал алертов обязан проверять сам себя: протухший токен даёт тишину,
  # неотличимую от исправности. jq не зовём — на части хостов его нет, поэтому
  # требуем и наличие "response", и отсутствие "error": одного вхождения
  # подстроки мало, слово response встречается и в телах ошибок VK.
  if printf '%s' "$resp" | grep -q '"response"' && ! printf '%s' "$resp" | grep -q '"error'; then
    return 0
  fi
  log "VK-SEND отвергнут: $(printf '%s' "$resp" | head -c 200)"
  return 1
}

# Маячок пишется в КОНЦЕ прогона: маячок, выставленный на входе, доказывал бы
# только то, что скрипт запустился, и упавший на середине прогон выглядел бы
# для пира живым.
write_beacon() {
  local channel="$1" body
  body="ts=$(date +%s) host=${SITE} channel=${channel} fails=${#FAILING[@]}"
  printf '%s\n' "$body" > "${STATE_DIR}/beacon.self.tmp" && mv "${STATE_DIR}/beacon.self.tmp" "${STATE_DIR}/beacon.self"
  BEACON_BODY="$body"
}

push_beacon() {
  [ "$PEER_TRANSPORT" = "ssh" ] || { echo "доставка маячка не требуется (${PEER_TRANSPORT})"; return 0; }
  if printf '%s\n' "$BEACON_BODY" | ssh_peer "umask 077; mkdir -p ${PEER_STATE_DIR}; cat > ${PEER_STATE_DIR}/peer.beacon.tmp && mv ${PEER_STATE_DIR}/peer.beacon.tmp ${PEER_STATE_DIR}/peer.beacon"; then
    echo "маячок доставлен ${PEER_LABEL}у"
    return 0
  fi
  echo "не смог доставить свой маячок ${PEER_LABEL}у — там решат, что мы умерли, и поднимут ложную тревогу"
  return 1
}

# ---------------------------------------------------------- разбор целей ----
# Формат строки: host|checks|http_path|expect_code
# checks — через запятую из {tls, http}. Пустые поля допустимы.

if [ -n "$TARGETS_FILE" ] && [ -r "$TARGETS_FILE" ]; then
  while IFS='|' read -r t_host t_checks t_path t_expect || [ -n "$t_host" ]; do
    # \r снимается со ВСЕХ полей: строка из двух полей уносит его в checks, из
    # трёх — в path, и цель тогда молча не проверяется или проверяется мусорным
    # URL. Файл из Windows-клона — самый вероятный источник.
    t_host="${t_host//$'\r'/}"; t_checks="${t_checks//$'\r'/}"
    t_path="${t_path//$'\r'/}"; t_expect="${t_expect//$'\r'/}"
    t_host="${t_host#"${t_host%%[![:space:]]*}"}"
    t_host="${t_host%"${t_host##*[![:space:]]}"}"
    case "$t_host" in ''|'#'*) continue ;; esac

    # Пробелы и регистр в checks не должны отключать проверку: «tls, HTTP» —
    # ровно то, что человек напишет, правя конфиг руками.
    t_checks="$(printf '%s' "${t_checks:-tls}" | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]')"
    for tok in ${t_checks//,/ }; do
      case "$tok" in
        tls|http) ;;
        *) TARGET_PROBLEMS="${TARGET_PROBLEMS}цель ${t_host}: неизвестный вид проверки «${tok}» — цель не опрашивается; " ;;
      esac
    done

    TARGET_HOSTS+=("$t_host")
    TARGET_CHECKS+=("$t_checks")
    TARGET_PATHS+=("${t_path:-/}")
    TARGET_EXPECT+=("${t_expect:-200}")
  done < "$TARGETS_FILE"
fi

# ------------------------------------------------------------- прогон -------

run_check_once config "Опрашивалка" check_config

for i in ${TARGET_HOSTS[@]+"${!TARGET_HOSTS[@]}"}; do
  host="${TARGET_HOSTS[$i]}"
  case ",${TARGET_CHECKS[$i]}," in
    *,tls,*)  run_check "tls:${host}"  "TLS ${host}"  check_tls "$host" ;;
  esac
  case ",${TARGET_CHECKS[$i]}," in
    *,http,*) run_check "http:${host}" "HTTP ${host}" check_http "$host" "${TARGET_PATHS[$i]}" "${TARGET_EXPECT[$i]}" ;;
  esac
done

for d in $CERT_DRIFT_DOMAINS; do
  run_check "drift:${d}" "Эфир против диска ${d}" check_cert_drift "$d"
done

if [ -n "$COPY_DRIFT_CHECK" ]; then
  run_check_once copy_drift "Копии скриптов против git" check_copy_drift
fi

if [ "$PEER_TRANSPORT" != "none" ]; then
  run_check peer_alive "Дед-мэн: ${PEER_LABEL}" check_peer_alive
  # Канал пира оцениваем, только если маячок вообще прочитался. Иначе смерть
  # пира превратилась бы в «Восстановлено: канал алертов» — одно сообщение
  # объявляло бы и смерть узла, и его выздоровление.
  if [ "$LAST_RC" = "0" ]; then
    run_check peer_channel "Канал алертов ${PEER_LABEL}а" check_peer_channel
  fi
fi

# Состояние канала с прошлого прогона: в маячок кладём то, что знаем ДО отправки,
# после отправки перепишем. Так пир узнает о поломке канала не позже, чем через
# один цикл, и узнает даже если сообщение отправить не удалось.
CHANNEL_FILE="${STATE_DIR}/channel.state"
CHANNEL_STATE="unknown"
[ -f "$CHANNEL_FILE" ] && CHANNEL_STATE="$(cat "$CHANNEL_FILE")"
BEACON_BODY=""
write_beacon "$CHANNEL_STATE"

if [ "$PEER_TRANSPORT" != "none" ]; then
  run_check beacon_push "Доставка маячка ${PEER_LABEL}у" push_beacon
fi

# ------------------------------------------------------------- сигналы ------

if [ ${#NEW_FAIL_LINES[@]} -gt 0 ] || [ ${#RECOVERY_LINES[@]} -gt 0 ]; then
  MSG="${ALERT_PREFIX} опрашивалка (${SITE})"$'\n'"$(date -u '+%Y-%m-%d %H:%M UTC')"
  for line in ${NEW_FAIL_LINES[@]+"${NEW_FAIL_LINES[@]}"}; do MSG="${MSG}"$'\n'"${line}"; done
  for line in ${RECOVERY_LINES[@]+"${RECOVERY_LINES[@]}"}; do MSG="${MSG}"$'\n'"${line}"; done
  if send_vk "$MSG"; then
    # Состояние фиксируем ТОЛЬКО после подтверждённой отправки — иначе
    # проваленный алерт был бы задедуплен и потерян навсегда.
    for k in ${PENDING_FAIL[@]+"${PENDING_FAIL[@]}"}; do date +%s > "$(state_file "$k")"; done
    for k in ${PENDING_CLEAR[@]+"${PENDING_CLEAR[@]}"}; do rm -f "$(state_file "$k")"; done
    CHANNEL_STATE="ok"
    log "ALERT отправлен (новых отказов: ${#NEW_FAIL_LINES[@]}, восстановлений: ${#RECOVERY_LINES[@]})"
  else
    CHANNEL_STATE="fail"
    log "ALERT НЕ отправлен — состояние не фиксируем, повторим в следующий прогон"
  fi
fi

# Пульс: раз в сутки. Молчание дольше суток означает, что умерла сама опрашивалка
# или канал алертов. Дед-мэн ловит это за минуты, пульс — независимая вторая
# страховка на случай, когда обе стороны молчат одинаково.
HEARTBEAT_FILE="${STATE_DIR}/heartbeat.date"
TODAY="$(date -u '+%Y-%m-%d')"
LAST_BEAT=""
[ -f "$HEARTBEAT_FILE" ] && LAST_BEAT="$(cat "$HEARTBEAT_FILE")"
if [ "$(date -u '+%H')" -ge "$HEARTBEAT_HOUR" ] 2>/dev/null && [ "$LAST_BEAT" != "$TODAY" ]; then
  if [ ${#FAILING[@]} -gt 0 ]; then
    BEAT="${ALERT_PREFIX} пульс (${SITE}): ${#FAILING[@]} проверок в отказе"
    for line in ${FAILING[@]+"${FAILING[@]}"}; do BEAT="${BEAT}"$'\n'"[X] ${line}"; done
  else
    BEAT="${ALERT_PREFIX} пульс (${SITE}): всё зелёное"
    for line in ${DETAILS[@]+"${DETAILS[@]}"}; do BEAT="${BEAT}"$'\n'"- ${line}"; done
  fi
  if send_vk "$BEAT"; then
    echo "$TODAY" > "$HEARTBEAT_FILE"
    CHANNEL_STATE="ok"
    log "HEARTBEAT отправлен"
  else
    CHANNEL_STATE="fail"
    log "HEARTBEAT не отправлен"
  fi
fi

# Канал проверен фактом отправки — обновляем маячок, чтобы пир увидел это
# состояние, а не то, что было до прогона.
printf '%s' "$CHANNEL_STATE" > "$CHANNEL_FILE"
write_beacon "$CHANNEL_STATE"
if [ "$PEER_TRANSPORT" = "ssh" ]; then
  push_beacon > /dev/null 2>&1 || log "PUSH повторная доставка маячка не удалась"
fi

log "RUN завершён, код ${EXIT_CODE} (проверок в отказе: ${#FAILING[@]})"
exit "$EXIT_CODE"
