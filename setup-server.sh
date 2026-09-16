#!/bin/bash
set -e

# ============================================================================
# setup-server.sh
# ----------------------------------------------------------------------------
# ПОЛНАЯ ИНИЦИАЛИЗАЦИЯ СЕРВЕРА ДЛЯ TODO-APP (PostgreSQL)
# ----------------------------------------------------------------------------
# ⚠️ ВАЖНО: Запускать ТОЛЬКО ОДИН РАЗ на чистом сервере (Ubuntu/Debian)
#           Требуются права root (запускать от пользователя root)
#
# Скрипт выполнит:
#   ✅ Установку системных обновлений и сертификатов
#   ✅ Установку Docker
#   ✅ Создание сети todo-network
#   ✅ Запуск PostgreSQL контейнера
#   ✅ Проверку готовности БД
#   ✅ Создание администратора приложения (автоматически при первом запуске)
#
# После выполнения — просто пушите код в master, деплой будет автоматическим.
# ============================================================================

if [[ $EUID -ne 0 ]]; then
  echo "❌ ОШИБКА: Скрипт должен запускаться от пользователя root!"
  echo "   Выполните: sudo ./setup-server.sh"
  exit 1
fi

echo "=========================================="
echo "🚀 Полная инициализация сервера todo-app"
echo "=========================================="
echo ""

# ============================================================================
# ШАГ 1: Системные обновления и сертификаты
# ============================================================================
echo "🔧 ШАГ 1: Обновление системы и сертификатов..."
apt-get update > /dev/null 2>&1
DEBIAN_FRONTEND=noninteractive apt-get install -y \
  ca-certificates \
  curl \
  gnupg \
  lsb-release \
  git > /dev/null 2>&1

# Обновляем корневые сертификаты для безопасного HTTPS-доступа
update-ca-certificates --fresh > /dev/null 2>&1
echo "   ✅ Система и сертификаты обновлены"

# ============================================================================
# ШАГ 2: Установка Docker
# ============================================================================
echo "🐳 ШАГ 2: Установка Docker..."

if ! command -v docker &> /dev/null; then
  # Добавляем официальный GPG-ключ Docker
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg | \
    gpg --dearmor -o /etc/apt/keyrings/docker.gpg > /dev/null 2>&1
  chmod a+r /etc/apt/keyrings/docker.gpg

  # Добавляем репозиторий Docker
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
    https://download.docker.com/linux/ubuntu \
    $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
    tee /etc/apt/sources.list.d/docker.list > /dev/null 2>&1

  apt-get update > /dev/null 2>&1
  DEBIAN_FRONTEND=noninteractive apt-get install -y docker-ce docker-ce-cli containerd.io > /dev/null 2>&1
  echo "   ✅ Docker установлен"
else
  echo "   ℹ️  Docker уже установлен — пропускаем"
fi

# ============================================================================
# ШАГ 3: Создание сети
# ============================================================================
echo "🕸️  ШАГ 3: Создание сети todo-network..."

if docker network inspect todo-network > /dev/null 2>&1; then
  echo "   ℹ️  Сеть todo-network уже существует"
else
  docker network create todo-network > /dev/null 2>&1
  echo "   ✅ Сеть todo-network создана"
fi

# ============================================================================
# ШАГ 4: Запуск PostgreSQL
# ============================================================================
echo "🗃️  ШАГ 4: Запуск PostgreSQL контейнера..."

# Проверяем, запущен ли контейнер
if [ "$(docker ps -q -f name=postgres-db)" ]; then
  echo "   ℹ️  Контейнер postgres-db уже запущен — пропускаем"
  exit 0
fi

# Read password from .env if exists, otherwise use default
PG_PASSWORD="postgres"
if [ -f /root/todo/.env ]; then
  ENV_PG_PASS=$(grep -E '^POSTGRES_PASSWORD=' /root/todo/.env | cut -d= -f2-)
  if [ -n "$ENV_PG_PASS" ]; then
    PG_PASSWORD="$ENV_PG_PASS"
  fi
fi

# Start container
docker run -d \
  --name postgres-db \
  -e POSTGRES_DB=todo \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD="$PG_PASSWORD" \
  -v postgres-data:/var/lib/postgresql/data \
  --network todo-network \
  --restart unless-stopped \
  --health-cmd="pg_isready -U postgres" \
  --health-interval=10s \
  --health-timeout=5s \
  --health-retries=10 \
  postgres:17 > /dev/null 2>&1

echo "   ✅ Контейнер postgres-db запущен"

# ============================================================================
# ШАГ 5: Ожидание готовности БД
# ============================================================================
echo "⏳ ШАГ 5: Ожидание готовности базы данных (макс. 60 секунд)..."

timeout=60
count=0
until docker inspect postgres-db --format='{{.State.Health.Status}}' 2>/dev/null | grep -q "healthy" || [ $count -ge $timeout ]; do
  sleep 1
  count=$((count + 1))
  printf "."
done
echo ""

if [ $count -lt $timeout ]; then
  echo "✅ База данных готова к работе!"
  echo ""
  echo "=========================================="
  echo "🎉 СЕРВЕР УСПЕШНО ИНИЦИАЛИЗИРОВАН!"
  echo "=========================================="
  echo ""
  echo "📋 Дальнейшие действия:"
  echo "   1. Запушьте код в ветку master:"
  echo "      git push origin master"
  echo ""
  echo "   2. Пайплайн GitHub Actions автоматически:"
  echo "      • соберёт образ приложения"
  echo "      • отправит его в Docker Hub"
  echo "      • развернёт контейнер todo-app на сервере"
  echo ""
  echo "💡 Администратор приложения будет создан автоматически"
  echo "   при первом запуске приложения (если таблица users пуста)."
  echo ""
  echo "⚠️  Повторный запуск этого скрипта НЕ ТРЕБУЕТСЯ!"
  echo "   (Данные БД сохраняются в volume и не потеряются при перезапусках)"
  echo ""
else
  echo "⚠️  База данных не стала здоровой за 60 секунд"
  echo "   Проверьте логи: docker logs postgres-db"
  exit 1
fi