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
#   прод   — /home/deploy/todo принадлежит непривилегированному deploy, туда же
#            ходит CI. Root-cron, исполняющий скрипт из такого каталога, — это
#            root каждые пять минут для всякого, кто может писать в этот каталог.
#            Поэтому на проде ставим root-owned копию в /root/monitoring.
#
# Плата за копию — возможное расхождение с git. Чтобы оно не было тихим,
# опрашивалка на проде сверяет копию с репозиторием отдельной проверкой
# (COPY_DRIFT_CHECK) и сообщает о разошедшихся файлах.

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
CONF_DIR=/root/monitoring
STATE_DIR=/var/lib/portfolio-monitor
CONF="${CONF_DIR}/portfolio-monitor.conf"

# CRLF чиним ДО тестов и по всем исполняемым файлам, включая заглушки без
# расширения: файл с \r в shebang не запустится, и тесты упали бы на этом, а не
# на своей сути. Маска *.sh одна такие файлы не покрывает.
sed -i 's/\r$//' "${SRC}"/*.sh "${SRC}"/tests/*.sh "${SRC}"/tests/stubs/* 2>/dev/null || true
chmod +x "${SRC}"/*.sh "${SRC}"/tests/*.sh "${SRC}"/tests/stubs/* 2>/dev/null || true

echo "=== Тесты опрашивалки (до установки) ==="
if ! bash "${SRC}/tests/portfolio-monitor.test.sh"; then
  echo "тесты не прошли — установка прервана" >&2
  exit 1
fi

mkdir -p "$CONF_DIR" "$STATE_DIR"
chmod 750 "$STATE_DIR"

install -m 0644 "${SRC}/portfolio-monitor.logrotate" /etc/logrotate.d/portfolio-monitor

# Каталог, из которого cron будет запускать скрипты (см. шапку).
if [ "$ROLE" = "peer" ]; then
  RUN_DIR="$CONF_DIR"
  install -m 0755 -o root -g root "${SRC}/portfolio-monitor.sh" "${RUN_DIR}/portfolio-monitor.sh"
  install -m 0755 -o root -g root "${SRC}/certbot-renew.sh"     "${RUN_DIR}/certbot-renew.sh"
else
  RUN_DIR="$SRC"
fi

# Конфиг НИКОГДА не перезаписываем: в нём host-specific значения.
if [ ! -f "$CONF" ]; then
  echo "--- создаю ${CONF} (профиль ${ROLE}) ---"
  {
    echo "ROLE=${ROLE}"
    echo "VK_CONF_FILE=${CONF_DIR}/monitor.conf"
    echo "STATE_DIR=${STATE_DIR}"
    echo "LOG_FILE=/var/log/portfolio-monitor.log"
    echo "ALERT_PREFIX=[portfolio]"
    echo "CERT_WARN_DAYS=21"
    echo "PEER_SILENCE_SEC=1800"
    if [ "$ROLE" = "external" ]; then
      echo "TARGETS_FILE=${SRC}/portfolio-targets.conf"
      echo "PEER_TRANSPORT=ssh"
      echo "PEER_LABEL=прод"
      echo "PEER_HOST=${PEER_HOST:-82.114.226.107}"
      echo "PEER_SSH_USER=root"
      echo "PEER_SSH_KEY=/root/.ssh/id_ed25519_backup"
      echo "PEER_STATE_DIR=${STATE_DIR}"
    else
      echo "PEER_TRANSPORT=local"
      echo "PEER_LABEL=стейдж"
      echo "CERT_DRIFT_DOMAINS=\"${CERT_DRIFT_DOMAINS:-todo.keepware.ru todo.mngerasimenko.ru keepware.ru vpscan.keepware.ru clickmebattle.keepware.ru clickmebattle.mngerasimenko.ru api.replyai.keepware.ru}\""
      # Root-owned копия обновляется только ручной установкой — сверяем её
      # с репозиторием, чтобы расхождение было видно, а не копилось молча.
      echo "COPY_DRIFT_CHECK=\"${RUN_DIR}:${SRC}\""
    fi
  } > "$CONF"
  chmod 600 "$CONF"
else
  echo "--- ${CONF} уже есть, не трогаю ---"
fi

# Учётка VK: без неё опрашивалка не сможет отправить ни одного алерта, и сторож
# сам станет тихим отказом. Проверяем существование, а не гадаем по пути.
VK_CONF="$(grep -m1 '^VK_CONF_FILE=' "$CONF" | cut -d= -f2-)"
if [ ! -r "$VK_CONF" ] || ! grep -q '^VK_TOKEN=' "$VK_CONF" 2>/dev/null; then
  echo "ВНИМАНИЕ: ${VK_CONF} не читается или без VK_TOKEN — алерты уходить не будут." >&2
  echo "          Опрашивалка это увидит сама (проверка «Опрашивалка») и напишет в лог," >&2
  echo "          но сообщить владельцу сможет только соседняя сторона." >&2
fi

# timeout 280 — потолок меньше окна cron. Зависший прогон иначе держал бы flock
# и отбивал все следующие запуски навсегда: опрашивалка выключилась бы молча.
# Убитый по таймауту прогон не обновит маячок, и пир об этом сообщит.
CRON_MON="*/5 * * * * timeout 280 ${RUN_DIR}/portfolio-monitor.sh >> /var/log/portfolio-monitor.log 2>&1"

if [ "$ROLE" = "peer" ]; then
  install -m 0644 "${SRC}/certbot-renew.logrotate" /etc/logrotate.d/certbot-renew
  # Смещение от начала часа — чтобы не толкаться с остальными задачами.
  CRON_CERT="17 * * * * ${RUN_DIR}/certbot-renew.sh >> /var/log/certbot-renew.log 2>&1"
else
  CRON_CERT=""
fi

# crontab -l возвращает 1 и когда crontab пуст, и когда прочитать его не удалось
# (права, обновление пакета cron, SELinux). Если не различить, второй случай
# затирает ВЕСЬ crontab: на стейдже вместе с ним уехал бы offsite-бэкап, на проде —
# replyai-probe, server-monitor и backup. Молча.
EXISTING="$(crontab -l 2>/dev/null)"
CRON_RC=$?
if [ "$CRON_RC" -ne 0 ] && ! crontab -l 2>&1 | grep -qi 'no crontab'; then
  echo "crontab -l вернул ошибку (${CRON_RC}) — не переписываю crontab" >&2
  exit 1
fi
if [ -n "$EXISTING" ]; then
  printf '%s\n' "$EXISTING" > "${CONF_DIR}/crontab.bak"
  chmod 600 "${CONF_DIR}/crontab.bak"
fi

# Снимаем то, что заменяем: старую внешнюю опрашивалку (её покрывает portfolio-monitor)
# и старую строку reload'а certbot (её заменяет certbot-renew.sh с --deploy-hook).
# Шаблон узкий намеренно: certbot на проде общий на пять проектов, и широкое
# «certbot renew» снесло бы чужую строку молча.
DROP='external-monitor\.sh|portfolio-monitor\.sh|certbot-renew\.sh|certbot certbot renew'
printf '%s\n' "$EXISTING" | grep -E "$DROP" | while IFS= read -r line; do
  [ -n "$line" ] && echo "  снимаю строку crontab: ${line}"
done

{
  printf '%s\n' "$EXISTING" | grep -Ev "$DROP" | grep -v '^$'
  echo "$CRON_MON"
  [ -n "$CRON_CERT" ] && echo "$CRON_CERT"
} | crontab -

echo "=== Установлено (${ROLE}) ==="
crontab -l | grep -E 'portfolio-monitor|certbot-renew' || true
echo
echo "Проверить руками: ${SRC}/portfolio-monitor.sh; echo rc=\$?"
echo "Лог:              tail -20 /var/log/portfolio-monitor.log"
