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
# Что теперь — ДВА независимых основания для reload, и этого не случайно:
#   1) --deploy-hook самого certbot оставляет маркер, когда серт реально обновился;
#   2) сверка «есть ли в архиве сертификат новее последнего reload'а» — она не зависит
#      ни от хука, ни от того, какой процесс выполнил продление.
# Второе основание нужно потому, что продлений на проде два цикла: этот скрипт и
# собственный 12-часовой цикл контейнера (docker-compose.yml). Если контейнер по
# какой-то причине крутится со старым entrypoint без хука, маркера не будет — и
# reload держится только на сверке архива.
#
# Маркер и метка снимаются только после успешного reload: не удалось — повторим
# в следующий прогон, а не потеряем продление молча.
#
# Установка: monitoring/install-portfolio-monitor.sh peer (cron ежечасно).

set -uo pipefail

: "${CERTBOT_CONTAINER:=certbot}"
: "${NGINX_CONTAINER:=nginx-proxy}"
: "${RELOAD_MARKER:=/etc/letsencrypt/.nginx-reload-needed}"
: "${RELOAD_STAMP:=/etc/letsencrypt/.nginx-reload-stamp}"
: "${CERT_ARCHIVE:=/etc/letsencrypt/archive}"

log() { printf '%s %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$1"; }

# docker inspect отдаёт 0 и для остановленного контейнера — спрашиваем состояние.
state="$(docker inspect -f '{{.State.Running}}' "$CERTBOT_CONTAINER" 2>/dev/null)"
if [ "$state" != "true" ]; then
  log "ERROR контейнер ${CERTBOT_CONTAINER} не запущен (state=${state:-нет контейнера}) — продление не выполнялось"
  exit 1
fi

docker exec "$CERTBOT_CONTAINER" certbot renew --quiet \
  --deploy-hook "touch ${RELOAD_MARKER}"
renew_rc=$?
[ "$renew_rc" -ne 0 ] && log "WARN certbot renew завершился с кодом ${renew_rc}"

reason=""

if docker exec "$CERTBOT_CONTAINER" test -f "$RELOAD_MARKER" 2>/dev/null; then
  reason="сработал deploy-hook"
elif ! docker exec "$CERTBOT_CONTAINER" test -f "$RELOAD_STAMP" 2>/dev/null; then
  # Первый прогон: метки ещё нет, состояние nginx относительно сертов неизвестно.
  reason="первый прогон, метка последнего reload'а отсутствует"
elif [ -n "$(docker exec "$CERTBOT_CONTAINER" find "$CERT_ARCHIVE" -name 'cert*.pem' -newer "$RELOAD_STAMP" 2>/dev/null)" ]; then
  # Страховка на случай, когда продление выполнил цикл без хука.
  reason="в архиве есть сертификат новее последнего reload'а"
fi

if [ -n "$reason" ]; then
  log "RELOAD-NEEDED ${reason} — перезагружаю ${NGINX_CONTAINER}"
  if docker exec "$NGINX_CONTAINER" nginx -s reload 2>&1; then
    docker exec "$CERTBOT_CONTAINER" sh -c "rm -f '${RELOAD_MARKER}' && touch '${RELOAD_STAMP}'" \
      || log "WARN reload прошёл, но маркер/метку снять не удалось — следующий прогон перезагрузит ещё раз"
    log "RELOADED ${NGINX_CONTAINER} перечитал конфигурацию"
  else
    log "ERROR reload ${NGINX_CONTAINER} не удался — маркер ОСТАВЛЕН, повтор в следующий прогон"
    exit 1
  fi
fi

exit "$renew_rc"
