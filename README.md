# Todo List

![Java](https://img.shields.io/badge/Java-17-007396?logo=java)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5.6-6DB33F?logo=spring)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-336791?logo=postgresql)
![Liquibase](https://img.shields.io/badge/Liquibase-migrations-2962FF?logo=liquibase)
![Docker](https://img.shields.io/badge/Docker-24+-2496ED?logo=docker)
![GitHub Actions](https://img.shields.io/badge/GitHub_Actions-CI%2FCD-2088FF?logo=github-actions)
![Swagger](https://img.shields.io/badge/Swagger-OpenAPI_3-85EA2D?logo=swagger)

REST API бэкенд для совместного управления списком задач. Система списков позволяет нескольким пользователям работать в общем пространстве, видеть задачи друг друга и отмечать их выполнение.

## 📱 Приложения

| Платформа | Ссылка | Технологии |
|-----------|--------|------------|
| 🌐 **Web** | **[todo.keepware.ru](https://todo.keepware.ru)** | React 18 + TypeScript + Vite + Tailwind CSS — [todolist-web](https://github.com/mngerasimenko/todolist-web) |
| 📱 **Android** | **[Скачать в RuStore](https://www.rustore.ru/catalog/app/ru.mngerasimenko.todolist)** | Kotlin + Jetpack Compose + Room + Hilt + Retrofit — [todolist-android](https://github.com/mngerasimenko/todolist-android) |
| ⚙️ **Backend** | `https://todo.keepware.ru/api` (Swagger UI закрыт, доступ только по запросу) | Java 17 + Spring Boot 3.5 + PostgreSQL + JWT (этот репозиторий) |

Все клиенты работают с единым REST API и используют JWT-аутентификацию.

---
<a href="https://github.com/devxb/gitanimals">
  <img src="https://render.gitanimals.org/lines/mngerasimenko?pet-id=824520970020148661" width="30%" height="100"/>
  <img src="https://render.gitanimals.org/lines/mngerasimenko?pet-id=821969379148787851" width="30%" height="100"/>
  <img src="https://render.gitanimals.org/lines/mngerasimenko?pet-id=823198080045774067" width="30%" height="100"/>  
</a>

## Demo (staging)

| Клиент | URL | Логин |
|--------|-----|-------|
| REST API | `http://185.244.172.45:8090/api/` | JWT |
| Android | **[todolist-android](https://github.com/mngerasimenko/todolist-android)** | `testuser@todolist.ru` / `testUser` |

---

## Возможности

- Совместная работа через списки задач (создание, вступление по паролю, роли ADMIN/USER)
- Приглашение в список по ссылке (ADMIN генерирует ссылку, 24 часа, многоразовая, email-отправка)
- Приватные задачи, видимые только создателю
- Цвета пользователей для визуальной идентификации задач
- Отметка задач как выполненных с фиксацией даты и исполнителя
- Email-верификация при регистрации (SMTP через reg.ru)
- Сброс пароля по email
- Смена email с повторной верификацией
- Политика конфиденциальности и пользовательское соглашение (`/privacy`, `/terms`)
- Вход по email (имена пользователей не уникальны)
- REST API с JWT аутентификацией (access JWT + opaque refresh tokens в БД)
- Ротация refresh-токенов с reuse detection (компрометация цепочки → отзыв всей семьи)
- Logout с Redis blacklist access-токенов (TTL = остаток токена, graceful degradation на in-memory fallback при сбое Redis) и отзывом refresh-токенов
- BCrypt хэширование паролей
- **Шифрование персональных данных в БД (AES-256-GCM):** email, имя пользователя, названия задач и списков шифруются через JPA `@Convert`. Поиск по email через blind index (HMAC-SHA256). Прозрачно для API-клиентов. Ключ — `ENCRYPTION_KEY` env, graceful degradation без ключа
- CORS для поддержки React SPA и прямых API-запросов
- Автоверсионирование (MAJOR.MINOR.PATCH) с проверкой совместимости Android-клиента
- Оптимистичная блокировка (`@Version`) на всех Entity — защита от потерянных обновлений
- Rate Limiting (Bucket4j, Token Bucket) — защита от брутфорса и спам-регистраций (7/5мин login, 3/час register, 100/мин API). Хранилище bucket'ов переключаемо: `rate-limit.storage=memory|redis`. В режиме `redis` (production) состояние live в Redis через `LettuceBasedProxyManager` — переживает рестарт контейнера и распределяется между инстансами
- Защита от race conditions (TOCTOU) — атомарные операции через UNIQUE constraints
- Миграции БД через Liquibase (безопасно для существующих данных)
- Автоматический CI/CD через GitHub Actions
- SMTP health check в мониторинге сервера
- Мониторинг сервера через VK-бот (алерты + интерактивные команды: /status, /jvm, /stats, /restart, /logs, /errors)
- Метрики приложения через Prometheus + Grafana (JVM, HTTP, HikariCP пул соединений, retention 30 дней)
- Интерактивная документация API (Swagger UI / OpenAPI 3)
- Контроль доступа: изменение и удаление аккаунта только владельцем, операции с задачами только для участников списка
- Каскадное удаление аккаунта: передача ADMIN, удаление пустых списков, сохранение публичных задач через системного пользователя
- Super-admin через email-whitelist (`SUPER_ADMIN_EMAILS` env) + `@PreAuthorize("@superAdminGuard.check(authentication)")` — ручные админ-операции (например, триггер напоминания неактивному пользователю). Для не-админов `/api/admin/**` маскируется под 404
- Runtime feature-flags через `/api/admin/flags/{name}/{value}` — переключение rate-limit, inactive-reminder scheduler'а, push-уведомлений и response-кэша без рестарта. Приоритет: runtime > env > enum-default. Рестарт сбрасывает runtime-override'ы (фича безопасности — забытое выключение защиты автоматически восстанавливается при следующем деплое)
- Response-кэш hot-paths через Spring Cache + Redis: `GET /api/users/me` (ключ = email), `GET /api/lists` (ключ = userId) и auth-lookup `user-auth` для `TodoUserDetailsService.loadUserByUsername` (отдельный `AuthUserDto` с email+password, ключ = email.toLowerCase). TTL 60 сек. Точечный `@CacheEvict` на всех мутациях User/TaskList. Runtime-выключение через feature flag `response-cache.enabled` + break-glass env `RESPONSE_CACHE_ENABLED=false`
- **Resilience:** при недоступности Redis приложение работает без кэша (graceful degradation через `CacheErrorHandler`), `/api/status` отдаёт `redis_healthy: false` (PING каждые 30 сек), VK-бот шлёт алерт раз в сутки. Метрики `cache_errors_total` + Grafana dashboard «Redis Cache (Spring)»
- Unit-тесты с проверкой покрытия (JaCoCo) + интеграционные на TestContainers (concurrency, Redis blacklist, Redis rate-limit, Redis user-auth cache) — отдельный запуск через `-Pintegration`

---

## Технологический стек

| Категория       | Технология                        | Версия  |
|-----------------|-----------------------------------|---------|
| **Язык**        | Java                              | 17      |
| **Backend**     | Spring Boot                       | 3.5.6   |
| **БД**          | PostgreSQL                        | 17      |
| **Миграции**    | Liquibase                         | (BOM)   |
| **Безопасность**| Spring Security + JWT (jjwt)      | 6.4.6   |
| **Сборка**      | Maven                             | 3.9     |
| **Тесты**       | JUnit 5 + Mockito + AssertJ       | -       |
| **Нагрузочные** | TestContainers (PostgreSQL + Redis) | (BOM) |
| **Кэш / Blacklist** | Redis (Spring Data Redis, Lettuce) | 7-alpine |
| **Покрытие**    | JaCoCo                            | 0.8.14  |
| **Контейнеры**  | Docker + Docker Compose           | 24+     |
| **Rate Limiting**| Bucket4j (Token Bucket, core + lettuce — distributed через Redis) | 8.14.0  |
| **Документация**| springdoc-openapi (Swagger UI)    | 2.8.6   |
| **Мониторинг**  | Spring Boot Actuator + Micrometer (порт 8091) | 3.5.6   |
| **Метрики**     | Prometheus                        | 2.54.1  |
| **Дашборды**    | Grafana                           | 11.3.0  |
| **CI/CD**       | GitHub Actions                    | -       |

---

## REST API

REST API с JWT-аутентификацией: аутентификация (регистрация, логин, refresh, email-верификация, сброс пароля), CRUD списков и задач, управление пользователями, приглашения по ссылке.

Все эндпоинты (кроме auth и status) требуют JWT-токен.

Интерактивная документация: Swagger UI (доступен локально: `http://localhost:8090/api/swagger-ui.html`)

Postman-коллекция: `postman/TodoList_API.postman_collection.json`

---

## Быстрый запуск

### Требования
- Docker и Docker Compose
- Или Java 17 + Maven 3.9 + PostgreSQL (для локального запуска)

### Локально (без Docker)

```bash
mvn spring-boot:run
```
Приложение будет доступно по адресу: http://localhost:8090

### Docker Compose

```bash
# Сборка JAR (обязательно перед docker build)
mvn clean package -DskipTests

# Собрать образы и запустить контейнеры в фоне
docker compose up -d --build
# Проверить статус контейнеров
docker compose ps
# Смотреть логи приложения в реальном времени
docker compose logs -f todo-app
# Остановить и удалить все контейнеры
docker compose down
```

### Тесты

```bash
# Unit-тесты (439 тестов, без Docker)
mvn test

# С отчётом покрытия
mvn test jacoco:report

# Нагрузочные тесты (требуют Docker)
mvn test -Pintegration -Djacoco.skip=true

# Все тесты (unit + нагрузочные)
mvn test -Pintegration
```

---

## CI/CD

Проект использует пайплайн `.github/workflows/deploy.yml`:

**Этап 1 — Тесты** (все PR и push в master):
- 439 unit-тестов + проверка покрытия JaCoCo (70% инструкций, 70% строк, 60% ветвлений, 80% методов)
- Нагрузочные тесты (3 шт.) запускаются отдельно: `mvn test -Pintegration` (требуют Docker)

**Этап 2 — Деплой** (push в master → staging автоматически, production только вручную):
- Сборка JAR + Docker-образ → push в Docker Hub (`mngerasimenko/todo-app:{sha}` + `latest`)
- SCP `docker-compose.yml` на сервер
- **Pre-deploy:** immutable digest текущего образа сохраняется в `deploy-prev.txt`, `pg_dump -Fc` пишется в `backups/pre-deploy/` (хранятся 5 последних)
- `docker compose pull todo-app` → `docker compose up -d todo-app`
- Миграция БД через Liquibase (автоматически при старте приложения)
- `depends_on: service_healthy` гарантирует готовность PostgreSQL
- Версия приложения: `APP_VERSION_MAJOR.APP_VERSION_MINOR.github.run_number` (автоинкремент PATCH)

**Этап 3 — Rollback** (только вручную): Actions → Run workflow → target = `rollback-production`, `target_sha` опционально. Подменяет `image:` в `docker-compose.yml` на digest из `deploy-prev.txt` (или указанный SHA), пересоздаёт только `todo-app` через `docker compose up -d --force-recreate`. Остальные контейнеры не трогаются. БД-миграции уже применены — при необходимости восстанавливай через `pg_restore` из `backups/pre-deploy/*.dump`.

Защита ветки master: обязательный PR + успешные тесты.

---

## Мониторинг сервера

Telegram-бот для мониторинга состояния сервера. Работает в двух режимах:

**Автоматические алерты** (cron, каждые 5 минут):
- RAM > 80%, Swap > 50%, Disk > 85%
- JVM Heap > 85% (через Actuator на порту 8091)
- Контейнер упал или API недоступен
- PostgreSQL не принимает соединения

**Интерактивные команды бота:**

| Команда | Описание |
|---------|----------|
| `/status` | Полный отчёт (RAM, Swap, Disk, контейнеры, API, backup) |
| `/jvm` | JVM-метрики (heap, non-heap, threads, HikariCP, GC) |
| `/stats` | Статистика использования (пользователи, списки, задачи, активность) |
| `/restart [контейнер]` | Перезапустить контейнер |
| `/logs [N]` | Последние N строк логов |
| `/errors` | Последние ошибки из логов |
| `/config` | Настройки алертов |
| `/help` | Справка |

Конфигурация: `monitoring/monitor.conf` (Telegram Bot Token + Chat ID, не коммитится). Шаблон: `monitoring/monitor.conf.example`.

**Автоматический backup PostgreSQL** (cron, ежедневно в 3:00):
- `monitoring/backup.sh` — дамп БД, хранение 7 дней

### Prometheus + Grafana

Метрики приложения собираются через Actuator `/actuator/prometheus` (Micrometer) и визуализируются в Grafana:

- **Prometheus** (`127.0.0.1:9090`) — scrape интервал 15 сек, retention 30 дней
- **Grafana** (`127.0.0.1:3001`) — provisioning datasource и дашбордов из `monitoring/grafana/`
  - JVM Micrometer (heap, GC, threads, classes)
  - Spring Boot Statistics (HTTP requests/errors, HikariCP, response time)
- Доступ к Grafana только через SSH-туннель: `ssh -L 3001:localhost:3001 deploy@<host>` → `http://localhost:3001`
- Для Windows есть скрипт быстрого запуска: `scripts/open-grafana.bat`
- Учётные данные admin — в GitHub Secret `GRAFANA_ADMIN_PASSWORD`

---

## Первоначальная настройка сервера

```bash
git clone https://github.com/mngerasimenko/todo.git
cd todo
chmod +x setup-server.sh
./setup-server.sh
```

Скрипт устанавливает Docker, создаёт сеть и запускает PostgreSQL. Выполняется один раз.

---

## Структура проекта

```
src/main/java/ru/mngerasimenko/todolist/
├── controller/      REST-контроллеры (7: App, Todo, User, TaskList, Auth, EmailTracking, Admin)
├── service/         Бизнес-логика (10 интерфейсов + 10 реализаций; TokenBlacklistServiceRedis — основная, TokenBlacklistServiceInMemory — fallback через композицию; AdminService — операции супер-админа)
├── repository/      Spring Data JPA (6 репозиториев, включая RefreshTokenRepository)
├── model/           JPA-сущности (User, Todo, TaskList, TaskListUser, TaskListRole, InviteToken, RefreshToken) + @Version
├── dto/             DTO + list/ + auth/ подпакеты (email-верификация, сброс пароля, приглашения, logout)
├── mapper/          Ручные мапперы (Todo, User, TaskList)
├── crypto/          Шифрование данных (CryptoService — AES-256-GCM + HMAC, EncryptedStringConverter — JPA @Convert, DataEncryptionMigration — одноразовая миграция при старте)
├── config/          OpenApiConfig (Swagger UI), UsageStatsEndpoint (Actuator), SuperAdminProperties (email-whitelist)
├── security/        Spring Security + JWT + Rate Limiting (Bucket4j, memory/redis) + SuperAdminGuard
├── settings/        AppProperties, EmailProperties (corsOrigins, версия, SMTP)
├── exception/       GlobalExceptionHandler + кастомные исключения (включая TokenExpiredException)
└── TodolistApplication.java

src/main/resources/db/migration/   Liquibase-миграции (master + 15 changeset-файлов, включая 017-encryption-fields, 018-email-hash-not-null)
src/main/resources/templates/      HTML-шаблоны email (верификация, сброс пароля, приглашение)
src/test/java/        Unit + integration тесты (controller, service, repository, mapper, security, crypto, concurrency, redis blacklist integration, redis rate-limit integration, redis user-auth cache, admin-endpoint)
postman/             Postman-коллекция + окружения
monitoring/          VK-мониторинг (скрипты, systemd-сервис, конфиг) + backup PostgreSQL
monitoring/prometheus/   Prometheus scrape-конфиг
monitoring/grafana/      Grafana provisioning: datasources, dashboards (JVM, Spring Boot Statistics)
```

---
## Связанные проекты

- **[todolist-web](https://github.com/mngerasimenko/todolist-web)** — React SPA (TypeScript + Vite + Tailwind CSS)
- **[todolist-android](https://github.com/mngerasimenko/todolist-android)** — нативный Android-клиент (Kotlin + Jetpack Compose)

---

## Лицензия

Проект распространяется под лицензией [MIT](LICENSE).
