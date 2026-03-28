# Todo List

![Java](https://img.shields.io/badge/Java-17-007396?logo=java)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5.6-6DB33F?logo=spring)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-336791?logo=postgresql)
![Liquibase](https://img.shields.io/badge/Liquibase-migrations-2962FF?logo=liquibase)
![Docker](https://img.shields.io/badge/Docker-24+-2496ED?logo=docker)
![GitHub Actions](https://img.shields.io/badge/GitHub_Actions-CI%2FCD-2088FF?logo=github-actions)
![Swagger](https://img.shields.io/badge/Swagger-OpenAPI_3-85EA2D?logo=swagger)

REST API бэкенд для совместного управления списком задач. Система списков позволяет нескольким пользователям работать в общем пространстве, видеть задачи друг друга и отмечать их выполнение. Клиенты: React SPA и нативное Android-приложение.

---
<a href="https://github.com/devxb/gitanimals">
  <img src="https://render.gitanimals.org/lines/mngerasimenko?pet-id=820662335556900499" width="30%" height="100"/>
  <img src="https://render.gitanimals.org/lines/mngerasimenko?pet-id=816406221692682508" width="30%" height="100"/>
  <img src="https://render.gitanimals.org/lines/mngerasimenko?pet-id=818351541146185755" width="30%" height="100"/>  
</a>

## Demo

| Клиент | URL | Логин |
|--------|-----|-------|
| React UI | **[https://todo.keepware.ru](https://todo.keepware.ru)** | `testuser@todolist.ru` / `testUser` |
| REST API | `https://todo.keepware.ru/api/` | JWT |
| Swagger UI | **[https://todo.keepware.ru/api/swagger-ui.html](https://todo.keepware.ru/api/swagger-ui.html)** | — |
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
- Logout с in-memory blacklist access-токенов и отзывом refresh-токенов
- BCrypt хэширование паролей
- CORS для поддержки React SPA и прямых API-запросов
- Автоверсионирование (MAJOR.MINOR.PATCH) с проверкой совместимости Android-клиента
- Оптимистичная блокировка (`@Version`) на всех Entity — защита от потерянных обновлений
- Rate Limiting (Bucket4j) — защита от брутфорса и спам-регистраций (5/мин login, 3/час register, 100/мин API)
- Защита от race conditions (TOCTOU) — атомарные операции через UNIQUE constraints
- Миграции БД через Liquibase (безопасно для существующих данных)
- Автоматический CI/CD через GitHub Actions
- SMTP health check в мониторинге сервера
- Мониторинг сервера через VK-бот (алерты + интерактивные команды: /status, /jvm, /stats, /restart, /logs, /errors)
- Интерактивная документация API (Swagger UI / OpenAPI 3)
- Контроль доступа: изменение и удаление аккаунта только владельцем, операции с задачами только для участников списка
- Каскадное удаление аккаунта: передача ADMIN, удаление пустых списков, сохранение публичных задач через системного пользователя
- 376 unit-тестов с проверкой покрытия (JaCoCo) + 3 нагрузочных теста (TestContainers, отдельный запуск)

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
| **Нагрузочные** | TestContainers (PostgreSQL)       | (BOM)   |
| **Покрытие**    | JaCoCo                            | 0.8.14  |
| **Контейнеры**  | Docker + Docker Compose           | 24+     |
| **Rate Limiting**| Bucket4j (Token Bucket)          | 8.14.0  |
| **Документация**| springdoc-openapi (Swagger UI)    | 2.8.6   |
| **Мониторинг**  | Spring Boot Actuator (порт 8091)  | 3.5.6   |
| **CI/CD**       | GitHub Actions                    | -       |

---

## REST API

### Аутентификация

| Метод | Эндпоинт | Описание |
|-------|----------|----------|
| POST | `/api/auth/register` | Регистрация (возвращает JWT) |
| POST | `/api/auth/login` | Вход (возвращает JWT) |
| POST | `/api/auth/refresh` | Обновление access токена |
| POST | `/api/auth/verify-email` | Подтверждение email по токену |
| POST | `/api/auth/resend-verification` | Повторная отправка верификации (JWT) |
| POST | `/api/auth/forgot-password` | Запрос сброса пароля |
| POST | `/api/auth/reset-password` | Установка нового пароля |
| POST | `/api/auth/change-email` | Смена email (JWT) |
| POST | `/api/auth/logout` | Выход: blacklist access + revoke refresh (JWT) |

### Списки задач

| Метод | Эндпоинт | Описание |
|-------|----------|----------|
| POST | `/api/lists` | Создать список (роль ADMIN) |
| POST | `/api/lists/join` | Вступить в список по паролю |
| GET | `/api/lists` | Мои списки |
| GET | `/api/lists/{id}/members` | Участники списка |
| GET | `/api/lists/{id}/todos` | Задачи списка (с учётом приватности) |
| POST | `/api/lists/{id}/invite` | Создать приглашение (только ADMIN) |
| GET | `/api/lists/invite/{token}` | Информация о приглашении (публичный) |
| POST | `/api/lists/invite/accept` | Принять приглашение |
| DELETE | `/api/lists/{id}` | Удалить список (только ADMIN) |
| DELETE | `/api/lists/{id}/leave` | Покинуть список |

### Задачи

| Метод | Эндпоинт | Описание |
|-------|----------|----------|
| POST | `/api/todos/create` | Создать задачу |
| GET | `/api/todos/all` | Все задачи |
| GET | `/api/todos/{id}` | Задача по ID |
| GET | `/api/todos/user/{userId}` | Задачи пользователя |
| PUT | `/api/todos/{id}` | Обновить задачу |
| PATCH | `/api/todos/{id}/done` | Отметить задачу выполненной |
| PATCH | `/api/todos/{id}/undone` | Снять отметку выполнения |
| DELETE | `/api/todos/{id}` | Удалить задачу |

### Пользователи

| Метод | Эндпоинт | Описание |
|-------|----------|----------|
| POST | `/api/users/create` | Создать пользователя |
| GET | `/api/users/all` | Все пользователи |
| GET | `/api/users/me` | Текущий пользователь (по JWT) |
| GET | `/api/users/{id}` | Пользователь по ID |
| PUT | `/api/users/{id}` | Обновить пользователя (только свой) |
| PUT | `/api/users/{id}/colors` | Обновить цвета задач (только свой) |
| DELETE | `/api/users/{id}` | Удалить пользователя (только свой) |

### Служебные

| Метод | Эндпоинт | Описание |
|-------|----------|----------|
| GET | `/api/status` | Статус, версия, min_android_version |
| GET | `/api/appName` | Название приложения |

Все эндпоинты (кроме auth, status, appName) требуют заголовок `Authorization: Bearer <token>`.

Интерактивная документация: **[Swagger UI](https://todo.keepware.ru/api/swagger-ui.html)** (локально: `http://localhost:8090/api/swagger-ui.html`)

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
# Unit-тесты (352 теста, без Docker)
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
- 376 unit-тестов + проверка покрытия JaCoCo (70% инструкций, 70% строк, 60% ветвлений, 80% методов)
- Нагрузочные тесты (3 шт.) запускаются отдельно: `mvn test -Pintegration` (требуют Docker)

**Этап 2 — Деплой** (только push в master):
- Сборка JAR + Docker-образ → push в Docker Hub (`mngerasimenko/todo-app:{sha}` + `latest`)
- SCP `docker-compose.yml` на сервер
- `docker compose pull todo-app` → `docker compose up -d todo-app`
- Миграция БД через Liquibase (автоматически при старте приложения)
- `depends_on: service_healthy` гарантирует готовность PostgreSQL
- Версия приложения: `APP_VERSION_MAJOR.APP_VERSION_MINOR.github.run_number` (автоинкремент PATCH)

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
- `monitoring/backup.sh` — дамп БД в `/root/backups/`, хранение 7 дней
- Восстановление: `docker exec -i postgres-db pg_restore -U postgres -d todo --clean --if-exists < backup.dump`

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
├── controller/      REST-контроллеры (5: App, Todo, User, TaskList, Auth)
├── service/         Бизнес-логика (8 интерфейсов + 8 реализаций, включая RefreshTokenService, TokenBlacklistService)
├── repository/      Spring Data JPA (6 репозиториев, включая RefreshTokenRepository)
├── model/           JPA-сущности (User, Todo, TaskList, TaskListUser, TaskListRole, InviteToken, RefreshToken) + @Version
├── dto/             DTO + list/ + auth/ подпакеты (email-верификация, сброс пароля, приглашения, logout)
├── mapper/          Ручные мапперы (Todo, User, TaskList)
├── config/          OpenApiConfig (Swagger UI), UsageStatsEndpoint (Actuator)
├── security/        Spring Security + JWT + Rate Limiting (Bucket4j)
├── settings/        AppProperties, EmailProperties (corsOrigins, версия, SMTP)
├── exception/       GlobalExceptionHandler + кастомные исключения (включая TokenExpiredException)
└── TodolistApplication.java

src/main/resources/db/migration/   Liquibase-миграции (master + 13 changeset-файлов)
src/main/resources/templates/      HTML-шаблоны email (верификация, сброс пароля, приглашение)
src/test/java/        376 тестов (controller, service, repository, mapper, security, concurrency)
postman/             Postman-коллекция + окружения
monitoring/          VK-мониторинг (скрипты, systemd-сервис, конфиг) + backup PostgreSQL
```

---
## Связанные проекты

- **[todolist-web](https://github.com/mngerasimenko/todolist-web)** — React SPA (TypeScript + Vite + Tailwind CSS)
- **[todolist-android](https://github.com/mngerasimenko/todolist-android)** — нативный Android-клиент (Kotlin + Jetpack Compose)

---

## Changelog

История изменений: [CHANGELOG.md](CHANGELOG.md)

---

## Лицензия

Проект распространяется под лицензией [MIT](LICENSE).
