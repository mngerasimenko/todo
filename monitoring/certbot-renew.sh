#!/usr/bin/env bash
#
# Продление сертификатов и перезагрузка nginx — ПО ФАКТУ продления.
#
# Что было до: строка в root-crontab прода
#   0 */12 * * * docker exec certbot certbot renew --quiet && docker exec nginx-proxy nginx -s reload
# У неё два дефекта. Первый: её нет в git — она живёт только в crontab одной машины,
# и переезд, пересборка хоста или затёртый crontab уносят её бесшумно, а проявится
# это через недели протухшим сертификатом из памяти nginx. Второй: reload висит на
# `&&`, то есть при ненулевом коде certbot'а nginx не перезагрузится, и оба факта
# будут молчаливыми.
#
# Что теперь — ТРИ основания для reload, и это не избыточность:
#   1) маркер от хука продления — certbot ставит его, когда серт реально обновился;
#   2) метки последнего reload нет — первый прогон, состояние nginx неизвестно;
#   3) в архиве есть сертификат новее метки последнего reload — это основание не
#      зависит ни от хука, ни от того, какой процесс выполнил продление.
#
# Хук кладётся ФАЙЛОМ в renewal-hooks/deploy, а не флагом --deploy-hook. Флаг certbot
# сохраняет в renewal-конфиг каждого продлённого сертификата (deploy_hook входит в
# STR_CONFIG_ITEMS): скрипт писал бы в конфиги всех пяти проектов общего certbot и
# вытеснял бы их собственные хуки. Файловый хук certbot исполняет при любом
# продлении — этим скриптом, 12-часовым циклом контейнера (docker-compose.yml) или
# ручным certonly.
#
# Метка ставится до reload, маркер снимается только после успешного reload и только
# если он не новее метки: не удалось — повторим в следующий прогон, а продление,
# пришедшее во время reload, не потеряется.
#
# Отметка работоспособности (RENEW_OK_FILE) пишется, когда прогон дошёл до конца, а
# reload и метка отработали. Её возраст сверяет опрашивалка на проде: сломанный
# reload или пропавший cron иначе видны только в логе, который никто не читает.
#
# Установка: monitoring/install-portfolio-monitor.sh peer (cron ежечасно).

set -uo pipefail

: "${CERTBOT_CONTAINER:=certbot}"
: "${NGINX_CONTAINER:=nginx-proxy}"
: "${RELOAD_MARKER:=/etc/letsencrypt/.nginx-reload-needed}"
: "${RELOAD_STAMP:=/etc/letsencrypt/.nginx-reload-stamp}"
: "${CERT_ARCHIVE:=/etc/letsencrypt/archive}"
: "${RELOAD_HOOK:=/etc/letsencrypt/renewal-hooks/deploy/nginx-reload-marker.sh}"
: "${RENEW_OK_FILE:=/var/lib/portfolio-monitor/certbot-renew.ok}"

log() { printf '%s %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$1"; }

# docker inspect отдаёт 0 и для остановленного контейнера — спрашиваем состояние.
state="$(docker inspect -f '{{.State.Running}}' "$CERTBOT_CONTAINER" 2>/dev/null)"
if [ "$state" != "true" ]; then
  log "ERROR контейнер ${CERTBOT_CONTAINER} не запущен (state=${state:-нет контейнера}) — продление не выполнялось"
  exit 1
fi

HOOK_BODY="#!/bin/sh
# Ставится monitoring/certbot-renew.sh: маркер говорит хосту перезагрузить nginx.
touch ${RELOAD_MARKER}"
# Сверяется и содержимое, и бит исполнения: неисполняемый файл хука certbot
# пропускает молча.
if [ "$(docker exec "$CERTBOT_CONTAINER" cat "$RELOAD_HOOK" 2>/dev/null)" != "$HOOK_BODY" ] \
   || ! docker exec "$CERTBOT_CONTAINER" test -x "$RELOAD_HOOK" 2>/dev/null; then
  if printf '%s\n' "$HOOK_BODY" | docker exec -i "$CERTBOT_CONTAINER" sh -c \
       "mkdir -p '${RELOAD_HOOK%/*}' && cat > '${RELOAD_HOOK}.tmp' && chmod 755 '${RELOAD_HOOK}.tmp' && mv '${RELOAD_HOOK}.tmp' '${RELOAD_HOOK}'"; then
    log "HOOK положен ${RELOAD_HOOK}"
  else
    log "WARN не смог положить хук ${RELOAD_HOOK} — продление без маркера подхватит сверка архива"
  fi
fi

docker exec "$CERTBOT_CONTAINER" certbot renew --quiet
renew_rc=$?
[ "$renew_rc" -ne 0 ] && log "WARN certbot renew завершился с кодом ${renew_rc}"

reason=""

if docker exec "$CERTBOT_CONTAINER" test -f "$RELOAD_MARKER" 2>/dev/null; then
  reason="сработал хук продления (deploy-hook)"
elif ! docker exec "$CERTBOT_CONTAINER" test -f "$RELOAD_STAMP" 2>/dev/null; then
  # Первый прогон: метки ещё нет, состояние nginx относительно сертов неизвестно.
  reason="первый прогон, метка последнего reload'а отсутствует"
elif [ -n "$(docker exec "$CERTBOT_CONTAINER" find "$CERT_ARCHIVE" -name 'cert*.pem' -newer "$RELOAD_STAMP" 2>/dev/null)" ]; then
  # Страховка на случай, когда хук не сработал или его не было.
  reason="в архиве есть сертификат новее последнего reload'а"
fi

stamp_failed=0
if [ -n "$reason" ]; then
  log "RELOAD-NEEDED ${reason} — перезагружаю ${NGINX_CONTAINER}"
  # Метка ставится ДО reload и встаёт на место после успеха: продление, записанное,
  # пока nginx перечитывает конфиг, окажется новее метки и не потеряется. Метка,
  # поставленная после reload, закрыла бы такое продление навсегда.
  if ! docker exec "$CERTBOT_CONTAINER" touch "${RELOAD_STAMP}.new" 2>/dev/null; then
    log "ERROR не смог поставить метку ${RELOAD_STAMP}.new"
    stamp_failed=1
  fi
  # Время файлов у busybox сравнивается секундами. Без паузы продление, закончившееся
  # в ту же секунду, что и метка, не оказалось бы новее неё и потерялось бы.
  sleep 1
  if docker exec "$NGINX_CONTAINER" nginx -s reload 2>&1; then
    # Маркер новее метки поставил хук уже во время reload: его продление этот reload
    # мог не застать, маркер остаётся до следующего прогона.
    docker exec "$CERTBOT_CONTAINER" sh -c \
      "mv -f '${RELOAD_STAMP}.new' '${RELOAD_STAMP}' && { [ '${RELOAD_MARKER}' -nt '${RELOAD_STAMP}' ] || rm -f '${RELOAD_MARKER}'; }" \
      || { log "ERROR reload прошёл, но метку/маркер обновить не удалось"; stamp_failed=1; }
    log "RELOADED ${NGINX_CONTAINER} перечитал конфигурацию"
  else
    log "ERROR reload ${NGINX_CONTAINER} не удался — маркер ОСТАВЛЕН, повтор в следующий прогон"
    exit 1
  fi
fi

# Метка не обновилась — nginx будет перезагружаться каждый прогон, а продление после
# этого reload может остаться неперечитанным. Отметку не пишем: опрашивалка сообщит.
if [ "$stamp_failed" = "1" ]; then
  log "ERROR метка последнего reload не обновлена — отметку работоспособности не пишу"
  exit 1
fi

# Код certbot renew в отметку не входит. certbot общий на пять проектов, и один чужой
# серт с уехавшим DNS держал бы проверку красной бессрочно, пряча то, ради чего она
# заведена, — сломанный reload или пропавший cron. Непродлевающийся серт ловит порог
# TLS в опрашивалке (21 день до конца).
date +%s > "$RENEW_OK_FILE" 2>/dev/null || log "WARN не смог записать отметку работоспособности ${RENEW_OK_FILE}"
exit "$renew_rc"
