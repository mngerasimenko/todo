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
#      (ровно тот класс отказа, что убил бота replyAI 14.08.2026); вместе со
#      сроком проверяется, что серт выписан на это имя;
#   3. там, где есть доступ к хосту, — сверка «серт в эфире против серта на диске»
#      (прямой детектор «nginx не перечитал продлённый серт») и проверка, что
#      продление сертификатов вообще отрабатывает;
#   4. дисциплина шума: сигнал на СМЕНУ состояния, сообщение о восстановлении,
#      все отказы прогона одним сообщением, состояние вне /tmp, лог с историей;
#   5. прогон укладывается в бюджет: сигналы по целям уходят до операций с
#      пиром, а сетевые операции урезаются так, чтобы cron-обёртка не убила
#      прогон раньше отправки.
#
# Роль хоста задаётся конфигом, скрипт один на обе машины. См. README.md.

set -uo pipefail

# Бюджет прогона отсчитывается от старта процесса — так же, как `timeout 280` крона.
RUN_STARTED="$(date +%s)"

# Потолок cron-обёртки приходит из самой строки cron: установщик пишет туда одно
# число — и в `timeout`, и сюда. Снимаем его ДО сорсинга конфига: иначе конфиг мог бы
# переопределить потолок и тем отключить проверку, которая его же и сторожит.
CRON_TIMEOUT_FROM_CRON="${CRON_TIMEOUT_SEC:-}"

CONF_FILE="${PORTFOLIO_MONITOR_CONF:-/root/monitoring/portfolio-monitor.conf}"
if [ -r "$CONF_FILE" ]; then
  # shellcheck disable=SC1090
  . "$CONF_FILE"
fi

: "${STATE_DIR:=/var/lib/portfolio-monitor}"
: "${LOG_FILE:=/var/log/portfolio-monitor.log}"
: "${LOCK_FILE:=/run/portfolio-monitor.lock}"
# Файл-выключатель: пока он лежит, прогон не выполняется. Маячок при этом не
# обновляется, и сосед через PEER_SILENCE_SEC сообщит, что эта сторона молчит.
: "${DISABLED_FLAG:=/root/monitoring/portfolio-monitor.disabled}"
: "${VK_CONF_FILE:=}"
: "${TARGETS_FILE:=}"
# Роль хоста нужна явно: без неё «TARGETS_FILE потерялся из конфига» неотличимо
# от «целей и не должно быть», и внешняя опрашивалка превращается в вечный
# зелёный no-op с исправным маячком, которого дед-мэн по устройству не поймает.
: "${ROLE:=}"
# Проверки прода включаются ролью, а не ключами в конфиге: хост с конфигом из ранней
# ревизии установщика или написанным руками иначе молча остался бы без них.
# Выключить их из конфига нельзя: пустое значение считается незаданным и заменяется
# умолчанием. Выключать опрашивалку целиком — файлом-выключателем.
if [ "$ROLE" = "peer" ]; then
  : "${CERT_DRIFT_DOMAINS:=auto}"
  : "${RENEW_OK_FILE:=${STATE_DIR}/certbot-renew.ok}"
fi
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
# Льгота первого контакта. При выкатке одна сторона встаёт раньше другой, и пока
# пир ни разу не выходил на связь, «маячка нет» означает «пир ещё не установлен»:
# это пишется в лог и в пульс, но тревогу не поднимает. Льгота кончается при
# первом прочитанном маячке или по сроку — дольше молчать о пире нельзя.
# Сломанный ssh-ключ льготой не прикрыт: его сразу выдаёт доставка своего маячка.
: "${PEER_FIRST_CONTACT_GRACE_SEC:=172800}"
# Бюджет прогона. Cron обёрнут в `timeout 280`, и прогон, убитый снаружи, не
# успевает ни отправить алерт, ни обновить маячок. Сетевые операции урезаются так,
# чтобы уложиться в RUN_BUDGET_SEC − SEND_RESERVE_SEC. Если цели съели этот запас,
# после него остаются не больше двух отправок в VK по 15 с — по целям и по дед-мэну.
# Пробную отправку при исчерпанном бюджете пропускаем всегда, пульс — только если в
# прогоне уже что-то ушло; иначе пульс важнее экономии. Вместе это меньше 280: при
# 260/40 — 220 + 30 = 250 с.
: "${RUN_BUDGET_SEC:=260}"
: "${SEND_RESERVE_SEC:=40}"
# Берём снимок из окружения или умолчание. Значение из конфига сюда намеренно не
# попадает — см. комментарий у снимка выше.
CRON_TIMEOUT_SEC="${CRON_TIMEOUT_FROM_CRON:-280}"
# Список доменов для сверки «эфир против диска»; auto — все серты из
# CERT_LIVE_DIR контейнера certbot, перечисленные на каждом прогоне.
: "${CERT_DRIFT_DOMAINS:=}"
# "установленная_копия:копия_в_репозитории" — там, где cron исполняет не сам
# репозиторий, а root-owned копию, копия обновляется только ручной установкой.
# Без этой проверки расхождение накапливается молча — ровно то, что уже
# произошло на обеих машинах со server-monitor.sh и соседями.
: "${COPY_DRIFT_CHECK:=}"
# Сверяются только файлы, которые ставит установщик. В том же каталоге лежат
# старые скрипты, положенные руками, и они давно разошлись с git: сверка по
# маске *.sh давала бы вечный красный с первого прогона.
: "${COPY_DRIFT_FILES:=portfolio-monitor.sh certbot-renew.sh}"
: "${CERT_DRIFT_TOLERANCE_SEC:=3600}"
# Свежий серт на диске ещё ждёт reload: certbot-renew.sh перечитывает nginx раз в
# час. Считается от notBefore, который Let's Encrypt отводит на час назад, так что
# после выпуска у reload есть около двух часов.
: "${CERT_DRIFT_GRACE_SEC:=10800}"
: "${CERTBOT_CONTAINER:=certbot}"
: "${CERT_LIVE_DIR:=/etc/letsencrypt/live}"
# Отметка работоспособности certbot-renew.sh; пусто — проверка выключена.
: "${RENEW_OK_FILE:=}"
# certbot-renew.sh идёт ежечасно: 4 ч — это три пропущенных прогона подряд.
: "${RENEW_OK_MAX_AGE_SEC:=14400}"
# Пробная отправка при мёртвом канале — не чаще этого: иначе канал, который
# доставляет, но отвечает непонятно, получал бы пробу каждые пять минут.
: "${PROBE_INTERVAL_SEC:=1800}"

# Учётка VK — общая шина алертов портфеля. Держим ОДНУ копию токена на хосте:
# по умолчанию подтягиваем её из конфига соседнего монитора, а не дублируем.
if [ -n "$VK_CONF_FILE" ] && [ -r "$VK_CONF_FILE" ]; then
  # shellcheck disable=SC1090
  . "$VK_CONF_FILE"
fi

# Числовые пороги. Нечисловое значение молча выключило бы проверку: сравнение
# `[ "4h" -gt … ]` даёт ошибку, то есть «ложь», и отказ не наступает никогда.
CONFIG_PROBLEMS=""
for v in RUN_BUDGET_SEC:260 SEND_RESERVE_SEC:40 PEER_FIRST_CONTACT_GRACE_SEC:172800 \
         PEER_SILENCE_SEC:1800 PEER_CLOCK_TOLERANCE_SEC:300 PEER_SSH_TIMEOUT:30 \
         TLS_TIMEOUT:8 HTTP_TIMEOUT:20 RETRY_DELAY:10 CERT_WARN_DAYS:21 SLOW_MS:10000 \
         HEARTBEAT_HOUR:6 CERT_DRIFT_TOLERANCE_SEC:3600 CERT_DRIFT_GRACE_SEC:10800 \
         RENEW_OK_MAX_AGE_SEC:14400 PROBE_INTERVAL_SEC:1800 CRON_TIMEOUT_SEC:280; do
  v_name="${v%%:*}"
  v_default="${v#*:}"
  case "${!v_name}" in
    ''|*[!0-9]*)
      CONFIG_PROBLEMS="${CONFIG_PROBLEMS}${v_name}=«${!v_name}» не число, взято ${v_default}; "
      printf -v "$v_name" '%s' "$v_default"
      ;;
    *)
      # Ведущий ноль в арифметике bash означает восьмеричную запись: `080` падает с
      # ошибкой и молча выключает урезание по бюджету, `0260` тихо превращается в 176.
      # Нормализуем в десятичное, а не браним: `06` в часе пульса — ровно то, что
      # человек пишет по привычке из cron.
      printf -v "$v_name" '%s' "$(( 10#${!v_name} ))"
      ;;
  esac
done

# Бюджет и резерв проверяются в паре: держать их согласованными — обязанность кода,
# а не только README. Боевым считается бюджет от 60 с; крошечные бюджеты из тестов
# под правила не подпадают.
if [ "$RUN_BUDGET_SEC" -ge 60 ]; then
  # Запасные значения ВЫВОДИМ из потолка, а не прибиваем числом: под обёрткой меньше
  # 280 подстановка 260/40 не умещается ровно так же, как исходный конфиг, и
  # «починка» сама нарушала бы инвариант, ради которого делается.
  FALLBACK_RESERVE=40
  FALLBACK_BUDGET=$(( CRON_TIMEOUT_SEC - 31 + FALLBACK_RESERVE ))
  [ "$FALLBACK_BUDGET" -gt 260 ] && FALLBACK_BUDGET=260
  if [ "$((FALLBACK_BUDGET - FALLBACK_RESERVE))" -lt 30 ]; then
    # Под такой обёрткой не помещается ни одна осмысленная пара значений. Сказать об
    # этом честно лучше, чем подставить числа, которые всё равно не лезут.
    CONFIG_PROBLEMS="${CONFIG_PROBLEMS}cron-обёртка ${CRON_TIMEOUT_SEC} с мала для опрашивалки (нужно от 100), считаю по 280; "
    FALLBACK_BUDGET=260
    FALLBACK_RESERVE=40
  fi
  # Резерв, как его написал человек: после подмены сообщать исходное значение, иначе
  # владелец пойдёт искать в конфиге число, которого там нет.
  RESERVE_AS_WRITTEN="$SEND_RESERVE_SEC"
  # Резерв меньше 30 с — прогон не успеет отправить алерт: две отправки по 15 с
  # уедут за cron-обёртку.
  if [ "$SEND_RESERVE_SEC" -lt 30 ]; then
    CONFIG_PROBLEMS="${CONFIG_PROBLEMS}SEND_RESERVE_SEC=${RESERVE_AS_WRITTEN} мало при RUN_BUDGET_SEC=${RUN_BUDGET_SEC}, взято ${FALLBACK_RESERVE}; "
    SEND_RESERVE_SEC="$FALLBACK_RESERVE"
  fi
  # Резерв съел бюджет: budget_for обнулит каждую операцию, и опрашивалка ответит
  # «не выполнялось» по всем целям разом — молчаливая слепота под видом работы.
  if [ "$((RUN_BUDGET_SEC - SEND_RESERVE_SEC))" -lt 30 ]; then
    CONFIG_PROBLEMS="${CONFIG_PROBLEMS}SEND_RESERVE_SEC=${RESERVE_AS_WRITTEN} не оставляет времени на проверки при RUN_BUDGET_SEC=${RUN_BUDGET_SEC}, взято ${FALLBACK_BUDGET}/${FALLBACK_RESERVE}; "
    RUN_BUDGET_SEC="$FALLBACK_BUDGET"
    SEND_RESERVE_SEC="$FALLBACK_RESERVE"
  fi
  # Сетевое окно плюс две отправки обязаны уместиться в cron-обёртку. Иначе прогон
  # убивают в середине сети: ни алерта, ни маячка — и сосед через полчаса сигналит
  # про этот хост вместо настоящей причины отказа.
  if [ "$((RUN_BUDGET_SEC - SEND_RESERVE_SEC + 30))" -ge "$CRON_TIMEOUT_SEC" ]; then
    CONFIG_PROBLEMS="${CONFIG_PROBLEMS}RUN_BUDGET_SEC=${RUN_BUDGET_SEC} не умещается в cron-обёртку ${CRON_TIMEOUT_SEC} с, взято ${FALLBACK_BUDGET}/${FALLBACK_RESERVE}; "
    RUN_BUDGET_SEC="$FALLBACK_BUDGET"
    SEND_RESERVE_SEC="$FALLBACK_RESERVE"
  fi
fi

BUDGET_MSG="бюджет прогона исчерпан (${RUN_BUDGET_SEC} с, из них ${SEND_RESERVE_SEC} с оставлены на отправку) — не выполнялось"
GRACE_MARK="ещё ни разу не выходил на связь"

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

if [ -f "$DISABLED_FLAG" ]; then
  log "DISABLED опрашивалка выключена файлом ${DISABLED_FLAG} — прогон не выполняется"
  exit 0
fi

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

# Сколько секунд осталось до конца бюджета за вычетом резерва на отправку.
budget_left() {
  printf '%s' "$(( RUN_STARTED + RUN_BUDGET_SEC - SEND_RESERVE_SEC - $(date +%s) ))"
}

# Сколько секунд можно отдать сетевой операции: не больше желаемого и не больше
# остатка бюджета. 0 — операцию не начинать: `timeout 0` у GNU означает «без
# ограничения», передавать его нельзя.
budget_for() {
  local want="$1" left
  left="$(budget_left)"
  [ "$left" -lt "$want" ] && want="$left"
  [ "$want" -lt 3 ] && want=0
  printf '%s' "$want"
}

# Строка чужого происхождения (маячок пира) в тексте сообщения: только печатные
# ASCII-символы и не длиннее 120. Мусорный маячок иначе раздул бы сообщение за
# лимит VK, и отвергнутая отправка срывала бы все следующие алерты.
quote_foreign() { printf '%s' "$1" | LC_ALL=C tr -cd '[:print:]' | cut -c1-120; }

# Кладёт код в HTTP_CODE, время в HTTP_TIME. Тело намеренно не разбирается:
# критерий «здорового ответа» для чужих проектов пока не согласован, а гадать
# по телу — верный способ получить стену ложных срабатываний.
# URL уходит конфигом на stdin (-K -), а не аргументом: argv на хосте виден всем
# (/proc без hidepid), и локальные пользователи прочитали бы токен обычным ps.
http_get() {
  local raw last
  raw="$(printf 'url = "%s"\n' "$(cfg_escape "$1")" \
         | curl -sS -m "$2" -w $'\n%{http_code} %{time_total}' -K - 2>/dev/null)"
  last="${raw##*$'\n'}"
  HTTP_CODE="${last%% *}"
  HTTP_TIME="${last#* }"
}

# Сертификат, который хост ПРЕДЪЯВЛЯЕТ В ЭФИРЕ, разобранный x509 с переданными
# опциями. --foreground у timeout здесь и дальше: без него timeout уводит команду
# в свою группу процессов, и cron-обёртка, убивая прогон, до неё не достаёт —
# зависшее соединение доживает свой таймаут отдельно, держа унаследованную
# блокировку прогона.
wire_cert() {
  local t="$1" host="$2"; shift 2
  echo | timeout --foreground "$t" openssl s_client -connect "${host}:443" -servername "$host" 2>/dev/null \
    | openssl x509 -noout "$@" 2>/dev/null
}

# timeout снаружи обязателен: ConnectTimeout покрывает только установку TCP.
# Если sshd принял соединение и завис (пир под нагрузкой, забитый диск), ssh
# висит бесконечно, прогон не заканчивается и flock отбивает ВСЕ следующие
# запуски cron навсегда — опрашивалка выключается молча, с кодом успеха.
ssh_peer() {
  local t="$1"; shift
  timeout --foreground "$t" \
    ssh -i "$PEER_SSH_KEY" -o BatchMode=yes -o ConnectTimeout=10 \
        -o ServerAliveInterval=5 -o ServerAliveCountMax=3 \
        -o StrictHostKeyChecking=accept-new -o LogLevel=ERROR \
        "${PEER_SSH_USER}@${PEER_HOST}" "$1" 2>/dev/null
}

# ------------------------------------------------------------- проверки -----
# Каждая печатает человеческую строку (на успехе — деталь для пульса,
# на отказе — причину) и возвращает 0 / 1.

check_tls() {
  local host="$1" t info enddate exp now days
  t="$(budget_for "$TLS_TIMEOUT")"
  if [ "$t" = "0" ]; then
    echo "${host}: ${BUDGET_MSG}"
    return 1
  fi
  info="$(wire_cert "$t" "$host" -enddate -checkhost "$host")"
  enddate="$(printf '%s\n' "$info" | sed -n 's/^notAfter=//p' | head -1)"
  if [ -z "$enddate" ]; then
    echo "TLS-рукопожатие с ${host} не отдало сертификат (хост недоступен или серт битый)"
    return 1
  fi
  # Имя проверяется тем же рукопожатием: nginx, потерявший vhost, отдаёт серт
  # соседнего проекта — срок у него живой, а клиент соединение не примет.
  # Требуем положительный ответ, а не отсутствие отрицательного: сменись формат
  # вывода openssl, проверка закричит, а не станет молча зелёной. Строку ищем
  # сопоставлением, а не конвейером в grep -q: под pipefail ранний выход grep
  # может оборвать запись printf, и код конвейера соврал бы.
  case $'\n'"${info}"$'\n' in
    *$'\n'"Hostname ${host} does match certificate"$'\n'*) ;;
    *)
      echo "в эфире серт не на это имя: ${host} отдаёт чужой сертификат"
      return 1
      ;;
  esac
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
  local host="$1" path="$2" expect="$3" problems="" ms t
  t="$(budget_for "$HTTP_TIMEOUT")"
  if [ "$t" = "0" ]; then
    echo "${host}${path}: ${BUDGET_MSG}"
    return 1
  fi
  http_get "https://${host}${path}" "$t"
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
  local host="$1" t wire disk wire_epoch disk_epoch delta age
  t="$(budget_for "$TLS_TIMEOUT")"
  if [ "$t" = "0" ]; then
    echo "${host}: ${BUDGET_MSG}"
    return 1
  fi
  wire="$(wire_cert "$t" "$host" -startdate | sed -n 's/^notBefore=//p' | head -1)"
  if [ -z "$wire" ]; then
    echo "${host}: не смог снять сертификат из эфира"
    return 1
  fi
  # docker exec тоже под бюджетом: зависший dockerd иначе растягивал бы прогон до
  # убийства cron-обёрткой — без алертов и без маячка.
  t="$(budget_for 15)"
  if [ "$t" = "0" ]; then
    echo "${host}: ${BUDGET_MSG}"
    return 1
  fi
  disk="$(timeout --foreground "$t" docker exec "$CERTBOT_CONTAINER" openssl x509 -noout -startdate \
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
    age=$(( $(date +%s) - disk_epoch ))
    # Льгота — только новому расхождению. Если дрейф уже в отказе, свежий серт на
    # диске означает перевыпуск без reload, а не выздоровление: льгота прислала бы
    # ложное «Восстановлено», пока nginx отдаёт всё тот же старый серт.
    if [ "$age" -lt "$CERT_DRIFT_GRACE_SEC" ] && [ ! -f "$(state_file "drift:${host}")" ]; then
      echo "${host}: серт на диске выпущен $(( age / 60 )) мин назад (по notBefore) и ждёт перечитывания nginx"
      return 0
    fi
    echo "${host}: на диске серт от $(date -u -d "@${disk_epoch}" '+%d.%m'), в эфире от $(date -u -d "@${wire_epoch}" '+%d.%m') — nginx не перечитал продлённый серт"
    return 1
  fi
  echo "${host}: эфир и диск совпадают"
  return 0
}

DRIFT_LIST_PROBLEM=""
CERT_DRIFT_LIST=""

# Список сертов certbot для сверки «эфир против диска». Выполняется в основном
# процессе: результат нужен и циклу сверок, и проверке check_drift_list.
list_cert_domains() {
  local t
  DRIFT_LIST_PROBLEM=""
  CERT_DRIFT_LIST=""
  t="$(budget_for 15)"
  if [ "$t" = "0" ]; then
    DRIFT_LIST_PROBLEM="список сертов certbot: ${BUDGET_MSG}"
    return
  fi
  CERT_DRIFT_LIST="$(timeout --foreground "$t" docker exec "$CERTBOT_CONTAINER" ls -1 "$CERT_LIVE_DIR" 2>/dev/null | grep -vx 'README' | tr '\n' ' ')"
  [ -n "${CERT_DRIFT_LIST// /}" ] \
    || DRIFT_LIST_PROBLEM="не смог перечислить сертификаты (${CERTBOT_CONTAINER}:${CERT_LIVE_DIR}) — сверка «эфир против диска» не выполнялась"
}

check_drift_list() {
  if [ -n "$DRIFT_LIST_PROBLEM" ]; then
    echo "$DRIFT_LIST_PROBLEM"
    return 1
  fi
  echo "сертов для сверки «эфир против диска»: $(printf '%s\n' $CERT_DRIFT_DOMAINS | grep -c .)"
  return 0
}

# Продление сертификатов отрабатывает: certbot-renew.sh обновляет отметку после
# каждого прогона, где reload и метка отработали. Сломанный reload nginx или
# пропавший cron иначе видны только в логе, который никто не читает.
check_renew_ok() {
  local ts age
  ts="$(cat "$RENEW_OK_FILE" 2>/dev/null)"
  case "$ts" in
    ''|*[!0-9]*)
      echo "нет отметки работоспособности certbot-renew.sh (${RENEW_OK_FILE}) — скрипт не отрабатывал с установки или отметку стёрли; см. /var/log/certbot-renew.log"
      return 1 ;;
  esac
  age=$(( $(date +%s) - ts ))
  if [ "$age" -gt "$RENEW_OK_MAX_AGE_SEC" ]; then
    echo "certbot-renew.sh не отрабатывал успешно $(( age / 3600 )) ч — не запущен контейнер certbot, не проходит reload nginx, не обновляется метка или пропал cron; см. /var/log/certbot-renew.log"
    return 1
  fi
  echo "certbot-renew: успешный прогон $(( age / 60 )) мин назад"
  return 0
}

# --------------------------------------------------------- дед-мэн ----------

PEER_RC=0
PEER_BEACON=""

# Маячок пира читается ОДИН раз за прогон, в основном процессе, и обе проверки
# дед-мэна разбирают одну и ту же строку. Проверки исполняются в подоболочке
# run_check и присвоить глобальную переменную не могут, поэтому раньше каждая
# читала маячок сама: второе чтение, моргнув, превращало «канал пира сломан»
# в ложное «Восстановлено».
# PEER_RC — код транспорта; budget — чтение не начиналось, бюджет прогона исчерпан.
fetch_peer_beacon() {
  local out="" t
  case "$PEER_TRANSPORT" in
    local)
      out="$(cat "${STATE_DIR}/peer.beacon" 2>/dev/null)"; PEER_RC=$? ;;
    ssh)
      t="$(budget_for "$PEER_SSH_TIMEOUT")"
      if [ "$t" = "0" ]; then
        PEER_RC=budget
      else
        out="$(ssh_peer "$t" "cat '${PEER_STATE_DIR}/beacon.self'")"; PEER_RC=$?
      fi
      ;;
    *) PEER_RC=1 ;;
  esac
  PEER_BEACON="$(printf '%s' "$out" | head -1)"
}

beacon_field() {
  local beacon="$1" name="$2" rest
  case " $beacon " in
    *" ${name}="*) rest="${beacon#*${name}=}"; printf '%s' "${rest%% *}" ;;
    *) printf '' ;;
  esac
}

# Отказ дед-мэна при пустом маячке — с учётом льготы первого контакта.
peer_absent() {
  local reason="$1" since now left
  if [ -f "${STATE_DIR}/peer.seen" ]; then
    echo "$reason"
    return 1
  fi
  now="$(date +%s)"
  since="$(cat "${STATE_DIR}/peer.first_run" 2>/dev/null)"
  case "$since" in
    ''|*[!0-9]*)
      # Без даты первого прогона льгота считается истёкшей: пустой файл (например,
      # на первом прогоне кончилось место) иначе продлевал бы её на каждом прогоне.
      echo "${PEER_LABEL} ни разу не вышел на связь, а дата первого прогона потеряна — льгота считается истёкшей: ${reason}"
      return 1
      ;;
  esac
  left=$(( since + PEER_FIRST_CONTACT_GRACE_SEC - now ))
  if [ "$left" -gt 0 ]; then
    log "GRACE ${PEER_LABEL} ${GRACE_MARK}, до конца льготы $(( left / 60 )) мин: ${reason}"
    echo "${PEER_LABEL} ${GRACE_MARK} (льгота первого контакта ещё $(( left / 3600 )) ч): ${reason}"
    return 0
  fi
  echo "${PEER_LABEL} ни разу не вышел на связь за $(( (now - since) / 3600 )) ч с первого прогона: ${reason}"
  return 1
}

check_peer_alive() {
  local beacon="$PEER_BEACON" ts age
  if [ "$PEER_RC" = "budget" ]; then
    echo "маячок ${PEER_LABEL}а не читали: ${BUDGET_MSG}"
    return 1
  fi
  if [ -z "$beacon" ]; then
    # Различаем «не смогли подключиться» и «подключились, а файла нет»: иначе
    # протухший ssh-ключ на нашей стороне отправит владельца чинить чужую машину.
    if [ "$PEER_TRANSPORT" = "ssh" ] && [ "$PEER_RC" = "255" ]; then
      peer_absent "не встало ssh-соединение с ${PEER_LABEL}ом (${PEER_SSH_USER}@${PEER_HOST}) — машина недоступна либо не принят наш ключ"
      return
    fi
    if [ "$PEER_TRANSPORT" = "ssh" ] && [ "$PEER_RC" = "124" ]; then
      peer_absent "чтение маячка ${PEER_LABEL}а не уложилось в таймаут — соединение встало и висит"
      return
    fi
    peer_absent "маячок ${PEER_LABEL}а не читается — там не пишется маячок или сторона молчит"
    return
  fi
  # Маячок есть — сторона пира существует, льгота первого контакта кончилась.
  : > "${STATE_DIR}/peer.seen" 2>/dev/null
  ts="$(beacon_field "$beacon" ts)"
  case "$ts" in
    ''|*[!0-9]*)
      echo "маячок ${PEER_LABEL}а не разбирается: «$(quote_foreign "$beacon")»"
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
# Зовётся ТОЛЬКО когда маячок прочитан и check_peer_alive в этом же прогоне
# отработал успешно — см. блок прогона. Иначе смерть пира выглядела бы как
# «канал восстановился»: пустой маячок не несёт channel=fail, проверка вернула бы
# 0, и run_check напечатал бы «Восстановлено» о том самом узле, чью смерть
# только что объявили.
check_peer_channel() {
  local ch fails
  ch="$(quote_foreign "$(beacon_field "$PEER_BEACON" channel)")"
  fails="$(quote_foreign "$(beacon_field "$PEER_BEACON" fails)")"
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
  local installed="${COPY_DRIFT_CHECK%%:*}" repo="${COPY_DRIFT_CHECK#*:}" diverged="" f
  if [ ! -d "$installed" ] || [ ! -d "$repo" ]; then
    echo "COPY_DRIFT_CHECK указывает на несуществующий каталог (${COPY_DRIFT_CHECK})"
    return 1
  fi
  for f in $COPY_DRIFT_FILES; do
    if [ ! -f "${installed}/${f}" ]; then
      diverged="${diverged}${f} (копии нет) "
    elif ! cmp -s "${installed}/${f}" "${repo}/${f}"; then
      diverged="${diverged}${f} "
    fi
  done
  if [ -n "$diverged" ]; then
    echo "установленные копии разошлись с репозиторием: ${diverged%% }— на машине крутится не то, что в git; перезапусти установщик"
    return 1
  fi
  echo "копии совпадают с репозиторием"
  return 0
}

# Опрашивалка, которая не может проверить, обязана кричать, а не молчать зелёным.
check_config() {
  local problems=""
  # Роль задаёт, какие проверки включены (на проде — сверка сертов и отметка
  # продления). Нераспознанная роль означает молча выключенные проверки, а не
  # «по умолчанию»: конфиг, написанный руками, ошибается именно здесь.
  case "$ROLE" in
    external|peer) ;;
    *) problems="${problems}ROLE=«${ROLE}» — ожидалось external или peer, проверки роли не включены; " ;;
  esac
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
  [ -n "$CONFIG_PROBLEMS" ] && problems="${problems}${CONFIG_PROBLEMS}"
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

# Антифлап: одиночная сетевая яма не должна поднимать владельца среди ночи.
# Пауза одна за прогон, а не на каждую цель: при девяти целях пауза на каждую
# растянула бы прогон за окно cron. И только если бюджет её вмещает.
antiflap_pause() {
  [ "$ALREADY_WAITED" = "0" ] || return 0
  [ "$RETRY_DELAY" -gt 0 ] 2>/dev/null || return 0
  [ "$(budget_left)" -gt "$RETRY_DELAY" ] || return 0
  sleep "$RETRY_DELAY"
  ALREADY_WAITED=1
}

# Для проверок, которые ждать бессмысленно (сломанный конфиг за 10 секунд не
# починится): повтор идёт сразу, без паузы, и не съедает единственную за прогон
# антифлап-паузу.
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
    antiflap_pause
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
  local text resp rc msg_file cut l
  text="$(redact "$1")"
  if [ -z "${VK_TOKEN:-}" ] || [ -z "${VK_PEER_ID:-}" ]; then
    log "VK-SEND НЕТ УЧЁТКИ: алерт не ушёл — <<${text}>>"
    return 1
  fi
  # VK принимает до 4096 символов. Отвергнутое по длине сообщение срывало бы и все
  # следующие отправки, поэтому длинный текст обрезается — по границе строки, чтобы
  # не разрезать многобайтный символ.
  if [ "${#text}" -gt 3500 ]; then
    log "VK-SEND сообщение обрезано, полностью: <<${text}>>"
    cut=""
    while IFS= read -r l; do
      [ $(( ${#cut} + ${#l} + 1 )) -gt 3400 ] && break
      cut="${cut}${l}"$'\n'
    done <<< "$text"
    [ -n "$cut" ] || cut="${text:0:3400}"$'\n'
    text="${cut}… (обрезано, полностью — в ${LOG_FILE})"
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
  # random_id — в пределах int32: так его описывает часть документации VK.
  resp="$(printf 'url = "https://api.vk.com/method/messages.send"\ndata-urlencode = "access_token=%s"\ndata-urlencode = "peer_id=%s"\ndata-urlencode = "random_id=%s"\ndata-urlencode = "v=%s"\ndata-urlencode = "message@%s"\n' \
      "$(cfg_escape "${VK_TOKEN}")" \
      "$(cfg_escape "${VK_PEER_ID}")" \
      "$(( RANDOM * 32768 + RANDOM ))" \
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

# Файл пропуска: «с какого времени канал не доставляет, сколько попыток сорвалось,
# когда была последняя попытка».
CHANNEL_DOWN_FILE="${STATE_DIR}/channel.down"
SEND_ATTEMPTED=0
FAILED_SEND_LINES=0

# Отправка владельцу с учётом сломанного канала. Если прежние отправки срывались,
# в сообщение добавляется строка о пропуске. Недоставленное не досылается очередью:
# переигранное задним числом «Восстановлено» могло бы прийти, когда цель уже снова
# лежит. Но владелец узнаёт, с какого времени канал молчал и где искать пропущенное.
send_alert() {
  local msg="$1" since="" count="" last="" gap head
  SEND_ATTEMPTED=1
  [ -f "$CHANNEL_DOWN_FILE" ] && read -r since count last < "$CHANNEL_DOWN_FILE"
  case "$since" in ''|*[!0-9]*) since="" ;; esac
  case "$count" in ''|*[!0-9]*) count=0 ;; esac
  if [ -n "$since" ]; then
    gap="Канал алертов не доставлял сообщения с $(date -u -d "@${since}" '+%d.%m %H:%M') UTC, неудачных попыток отправки: ${count}. Что не дошло — в ${LOG_FILE}"
    # Сразу после заголовка: обрезка длинного сообщения съедает хвост, а после
    # удачной отправки файл пропуска удаляется — строка потерялась бы навсегда.
    case "$msg" in
      *$'\n'*) head="${msg%%$'\n'*}"; msg="${head}"$'\n'"${gap}"$'\n'"${msg#*$'\n'}" ;;
      *) msg="${msg}"$'\n'"${gap}" ;;
    esac
  fi
  if send_vk "$msg"; then
    rm -f "$CHANNEL_DOWN_FILE"
    CHANNEL_STATE="ok"
    return 0
  fi
  printf '%s %s %s\n' "${since:-$(date +%s)}" "$(( count + 1 ))" "$(date +%s)" > "$CHANNEL_DOWN_FILE" 2>/dev/null
  CHANNEL_STATE="fail"
  return 1
}

# Сколько секунд ждать до следующей попытки отправки при сломанном канале. Время
# «из будущего» (часы шагнули назад) откладывать попытку не должно.
send_retry_wait() {
  local last="" now wait
  [ -f "$CHANNEL_DOWN_FILE" ] || { printf '0'; return; }
  read -r _ _ last < "$CHANNEL_DOWN_FILE"
  case "$last" in ''|*[!0-9]*) last=0 ;; esac
  now="$(date +%s)"
  wait=$(( last + PROBE_INTERVAL_SEC - now ))
  [ "$wait" -gt "$PROBE_INTERVAL_SEC" ] && wait=0
  [ "$wait" -lt 0 ] && wait=0
  printf '%s' "$wait"
}

# Когда пульс отправлять можно. При сломанном канале он повторяется не чаще пробной
# отправки — иначе пробовал бы уйти каждые пять минут. Правило про бюджет не
# безусловное, оно объяснено у самой проверки ниже.
heartbeat_allowed() {
  local wait
  # Бюджет исчерпан — пульс пропускаем, только если в этом прогоне уже что-то ушло:
  # владелец тогда без сигнала не остался, а три отправки по 15 с после исчерпания
  # запаса выводят прогон за cron-обёртку. Если отправок не было (длящийся отказ, все
  # проверки в STILL-FAIL), пульс — единственный повторяющийся сигнал, и он важнее.
  if [ "$SEND_ATTEMPTED" = "1" ] && [ "$(budget_left)" -lt 0 ]; then
    log "HEARTBEAT пропущен: бюджет прогона исчерпан, а сигнал в этом прогоне уже ушёл"
    return 1
  fi
  wait="$(send_retry_wait)"
  if [ "$wait" -gt 0 ]; then
    log "HEARTBEAT пропущен: канал сломан, следующая попытка не раньше чем через $(( wait / 60 )) мин"
    return 1
  fi
  return 0
}

# Отправляет накопленные отказы и восстановления одним сообщением. Состояние
# проверок фиксируется ТОЛЬКО после подтверждённой отправки — иначе проваленный
# алерт был бы задедуплен и потерян навсегда. Строки сорвавшейся отправки остаются
# и уходят со следующей отправкой этого прогона, которая их и зафиксирует; иначе
# следующий прогон прислал бы тот же отказ второй раз.
flush_alerts() {
  local msg line k n
  n=$(( ${#NEW_FAIL_LINES[@]} + ${#RECOVERY_LINES[@]} ))
  [ "$n" -gt 0 ] || return 0
  # Отправка уже сорвалась в этом прогоне и с тех пор ничего не добавилось — повтор
  # съел бы ещё до 15 с бюджета ради того же ответа.
  [ "$n" -gt "$FAILED_SEND_LINES" ] || return 0
  msg="${ALERT_PREFIX} опрашивалка (${SITE})"$'\n'"$(date -u '+%Y-%m-%d %H:%M UTC')"
  for line in ${NEW_FAIL_LINES[@]+"${NEW_FAIL_LINES[@]}"}; do msg="${msg}"$'\n'"${line}"; done
  for line in ${RECOVERY_LINES[@]+"${RECOVERY_LINES[@]}"}; do msg="${msg}"$'\n'"${line}"; done
  if send_alert "$msg"; then
    for k in ${PENDING_FAIL[@]+"${PENDING_FAIL[@]}"}; do date +%s > "$(state_file "$k")"; done
    for k in ${PENDING_CLEAR[@]+"${PENDING_CLEAR[@]}"}; do rm -f "$(state_file "$k")"; done
    log "ALERT отправлен (новых отказов: ${#NEW_FAIL_LINES[@]}, восстановлений: ${#RECOVERY_LINES[@]})"
    NEW_FAIL_LINES=(); RECOVERY_LINES=(); PENDING_FAIL=(); PENDING_CLEAR=()
    FAILED_SEND_LINES=0
  else
    FAILED_SEND_LINES=$n
    log "ALERT НЕ отправлен — состояние не фиксируем, повторим следующей отправкой"
  fi
}

# Маячок пишется в КОНЦЕ проверок: маячок, выставленный на входе, доказывал бы
# только то, что скрипт запустился, и упавший на середине прогон выглядел бы
# для пира живым.
write_beacon() {
  local channel="$1" body
  body="ts=$(date +%s) host=${SITE} channel=${channel} fails=${#FAILING[@]}"
  printf '%s\n' "$body" > "${STATE_DIR}/beacon.self.tmp" && mv "${STATE_DIR}/beacon.self.tmp" "${STATE_DIR}/beacon.self"
  BEACON_BODY="$body"
}

push_beacon() {
  local t
  [ "$PEER_TRANSPORT" = "ssh" ] || { echo "доставка маячка не требуется (${PEER_TRANSPORT})"; return 0; }
  t="$(budget_for "$PEER_SSH_TIMEOUT")"
  if [ "$t" = "0" ]; then
    echo "свой маячок ${PEER_LABEL}у не доставлен: ${BUDGET_MSG} — там решат, что мы умерли"
    return 1
  fi
  if printf '%s\n' "$BEACON_BODY" | ssh_peer "$t" "umask 077; mkdir -p ${PEER_STATE_DIR}; cat > ${PEER_STATE_DIR}/peer.beacon.tmp && mv ${PEER_STATE_DIR}/peer.beacon.tmp ${PEER_STATE_DIR}/peer.beacon"; then
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

CHANNEL_FILE="${STATE_DIR}/channel.state"
CHANNEL_STATE="unknown"
[ -f "$CHANNEL_FILE" ] && CHANNEL_STATE="$(cat "$CHANNEL_FILE")"
[ -f "$CHANNEL_DOWN_FILE" ] && CHANNEL_STATE="fail"

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

# Список сертов для сверки берётся из certbot на каждом прогоне: замороженный в
# конфиге на дату установки, он не увидел бы сертификат, добавленный позже.
if [ "$CERT_DRIFT_DOMAINS" = "auto" ]; then
  list_cert_domains
  # Антифлап: docker, занятый перезапуском контейнера, иначе дал бы пару
  # «отказ / Восстановлено» на все сверки разом.
  case "$DRIFT_LIST_PROBLEM" in
    "не смог"*) antiflap_pause; list_cert_domains ;;
  esac
  CERT_DRIFT_DOMAINS="$CERT_DRIFT_LIST"
  run_check_once drift_list "Список сертификатов certbot" check_drift_list
fi

for d in $CERT_DRIFT_DOMAINS; do
  run_check "drift:${d}" "Эфир против диска ${d}" check_cert_drift "$d"
done

if [ -n "$COPY_DRIFT_CHECK" ]; then
  run_check_once copy_drift "Копии скриптов против git" check_copy_drift
fi

if [ -n "$RENEW_OK_FILE" ]; then
  run_check_once renew_ok "Продление сертификатов" check_renew_ok
fi

# Сигналы по целям уходят ДО любых операций с пиром. Раньше отправка стояла в
# самом конце, после чтения и доставки маячков по ssh с повторами: пир, который
# принимает TCP и висит, растягивал прогон за `timeout 280` крона, прогон убивали
# до отправки — и отказ целей не доходил до владельца, каждые пять минут заново.
flush_alerts

if [ "$PEER_TRANSPORT" != "none" ]; then
  [ -f "${STATE_DIR}/peer.first_run" ] || date +%s > "${STATE_DIR}/peer.first_run" 2>/dev/null
  fetch_peer_beacon
  # Антифлап транспорта: несостоявшееся чтение повторяем один раз.
  if [ "$PEER_TRANSPORT" = "ssh" ] && [ -z "$PEER_BEACON" ] && [ "$PEER_RC" != "budget" ]; then
    antiflap_pause
    fetch_peer_beacon
  fi
  run_check_once peer_alive "Дед-мэн: ${PEER_LABEL}" check_peer_alive
  # Канал пира оцениваем, только если маячок прочитан и пир признан живым. Иначе
  # смерть пира превратилась бы в «Восстановлено: канал алертов» — одно сообщение
  # объявляло бы и смерть узла, и его выздоровление.
  if [ "$LAST_RC" = "0" ] && [ -n "$PEER_BEACON" ]; then
    run_check_once peer_channel "Канал алертов ${PEER_LABEL}а" check_peer_channel
  fi
fi

# В маячок кладём то, что знаем о своём канале к этому моменту. Если отправки
# ниже его изменят, маячок перепишется и доставится ещё раз в конце прогона.
BEACON_BODY=""
write_beacon "$CHANNEL_STATE"
PUSH_OK=0
if [ "$PEER_TRANSPORT" = "ssh" ]; then
  run_check beacon_push "Доставка маячка ${PEER_LABEL}у" push_beacon
  [ "$LAST_RC" = "0" ] && PUSH_OK=1
fi
PUSHED_CHANNEL="$CHANNEL_STATE"

# ------------------------------------------------------------- сигналы ------

# Отказы дед-мэна и доставки маячка.
flush_alerts

# Пульс: раз в сутки. Молчание дольше суток означает, что умерла сама опрашивалка
# или канал алертов. Дед-мэн ловит это за минуты, пульс — независимая вторая
# страховка на случай, когда обе стороны молчат одинаково.
HEARTBEAT_FILE="${STATE_DIR}/heartbeat.date"
TODAY="$(date -u '+%Y-%m-%d')"
LAST_BEAT=""
[ -f "$HEARTBEAT_FILE" ] && LAST_BEAT="$(cat "$HEARTBEAT_FILE")"
if [ "$(date -u '+%H')" -ge "$HEARTBEAT_HOUR" ] 2>/dev/null && [ "$LAST_BEAT" != "$TODAY" ] && heartbeat_allowed; then
  if [ ${#FAILING[@]} -gt 0 ]; then
    BEAT="${ALERT_PREFIX} пульс (${SITE}): ${#FAILING[@]} проверок в отказе"
    for line in ${FAILING[@]+"${FAILING[@]}"}; do BEAT="${BEAT}"$'\n'"[X] ${line}"; done
    # Строка льготы — не отказ, но из пульса пропасть не должна: это единственное
    # место, где владелец видит, что пир так и не вышел на связь.
    for line in ${DETAILS[@]+"${DETAILS[@]}"}; do
      case "$line" in *"$GRACE_MARK"*) BEAT="${BEAT}"$'\n'"- ${line}" ;; esac
    done
  else
    BEAT="${ALERT_PREFIX} пульс (${SITE}): всё зелёное"
    for line in ${DETAILS[@]+"${DETAILS[@]}"}; do BEAT="${BEAT}"$'\n'"- ${line}"; done
  fi
  if send_alert "$BEAT"; then
    echo "$TODAY" > "$HEARTBEAT_FILE"
    log "HEARTBEAT отправлен"
  else
    log "HEARTBEAT не отправлен"
  fi
fi

# Канал сломался, а отправлять с тех пор было нечего: без пробной отправки
# `channel=fail` держался бы до суточного пульса, и сосед до ~20 ч сообщал бы о
# сломанном канале. Только если в прогоне не было других отправок и не чаще
# PROBE_INTERVAL_SEC. При исчерпанном бюджете пробу пропускаем безусловно — в отличие
# от пульса: проба не несёт нового содержания, а пульс при длящемся отказе остаётся
# единственным повторяющимся сигналом.
if [ "$SEND_ATTEMPTED" = "0" ] && { [ -f "$CHANNEL_DOWN_FILE" ] || [ "$CHANNEL_STATE" = "fail" ]; }; then
  PROBE_WAIT="$(send_retry_wait)"
  if [ "$(budget_left)" -lt 0 ]; then
    log "CHANNEL пробная отправка пропущена: бюджет прогона исчерпан"
  elif [ "$PROBE_WAIT" -le 0 ]; then
    if send_alert "${ALERT_PREFIX} опрашивалка (${SITE}): канал алертов снова доставляет сообщения"; then
      log "CHANNEL пробная отправка прошла, канал снова работает"
    else
      log "CHANNEL пробная отправка не прошла"
    fi
  else
    log "CHANNEL канал сломан, следующая пробная отправка не раньше чем через $(( PROBE_WAIT / 60 )) мин"
  fi
fi

# Канал проверен фактом отправки — обновляем маячок, чтобы пир увидел это
# состояние, а не то, что было до отправок. Повторная доставка — только если
# первая прошла: мёртвому пиру третья попытка за прогон ничего не даст.
printf '%s' "$CHANNEL_STATE" > "$CHANNEL_FILE"
write_beacon "$CHANNEL_STATE"
if [ "$PUSH_OK" = "1" ] && [ "$CHANNEL_STATE" != "$PUSHED_CHANNEL" ]; then
  PUSH_OUT="$(push_beacon)" || log "PUSH повторная доставка маячка не удалась: ${PUSH_OUT}"
fi

log "RUN завершён, код ${EXIT_CODE} (проверок в отказе: ${#FAILING[@]})"
exit "$EXIT_CODE"
