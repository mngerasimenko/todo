#!/bin/bash
# Скрипт первоначального получения SSL-сертификата Let's Encrypt
# Запускать ОДИН РАЗ на сервере от root
#
# Предварительные условия:
# 1. DNS A-запись todo.keepware.ru -> 185.244.172.45 уже настроена
# 2. Порты 80 и 443 свободны
# 3. Docker и Docker Compose установлены
# 4. Контейнеры todo-app, todo-web, postgres-db запущены в сети todo-network

set -e

DOMAIN="todo.keepware.ru"
EMAIL="mngerasimenko@gmail.com"

echo "=== Получение SSL-сертификата для $DOMAIN ==="

# 1. Создать volumes для certbot
docker volume create certbot-conf 2>/dev/null || true
docker volume create certbot-www 2>/dev/null || true

# 2. Создать временную конфигурацию nginx (только HTTP, для ACME challenge)
mkdir -p /tmp/nginx-ssl-init
cat > /tmp/nginx-ssl-init/default.conf << 'NGINX_CONF'
server {
    listen 80;
    server_name todo.keepware.ru;

    location /.well-known/acme-challenge/ {
        root /var/www/certbot;
    }

    location / {
        return 200 'SSL setup in progress';
        add_header Content-Type text/plain;
    }
}
NGINX_CONF

# 3. Запустить nginx с HTTP-only конфигурацией
echo "Запуск nginx (HTTP-only)..."
docker stop nginx-proxy 2>/dev/null || true
docker rm nginx-proxy 2>/dev/null || true
docker run -d \
  --name nginx-proxy \
  -p 80:80 \
  -v /tmp/nginx-ssl-init/default.conf:/etc/nginx/conf.d/default.conf:ro \
  -v certbot-www:/var/www/certbot \
  --network todo-network \
  nginx:alpine

sleep 3

# 4. Получить сертификат через certbot
echo "Запуск certbot..."
docker run --rm \
  -v certbot-conf:/etc/letsencrypt \
  -v certbot-www:/var/www/certbot \
  certbot/certbot certonly \
    --webroot \
    -w /var/www/certbot \
    -d "$DOMAIN" \
    --email "$EMAIL" \
    --agree-tos \
    --no-eff-email \
    --force-renewal

# 5. Остановить временный nginx
echo "Остановка временного nginx..."
docker stop nginx-proxy
docker rm nginx-proxy

# 6. Очистка временных файлов
rm -rf /tmp/nginx-ssl-init

echo ""
echo "=== SSL-сертификат успешно получен! ==="
echo ""
echo "Следующие шаги: "
echo "1. Запустите полный стек: docker compose up -d"
echo "2. Проверьте: curl -I https://$DOMAIN/api/status"
echo ""
echo "Автообновление сертификатов:"
echo "Добавьте в crontab (crontab -e):"
echo "0 */12 * * * docker exec certbot certbot renew --quiet && docker exec nginx-proxy nginx -s reload"
