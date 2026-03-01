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

## Demo

| Клиент | URL | Логин |
|--------|-----|-------|
| React UI | **[http://185.244.172.45:3000](http://185.244.172.45:3000)** | `testUser` / `testUser` |
| REST API | `http://185.244.172.45:8090/api/` | JWT |
| Swagger UI | **[http://185.244.172.45:8090/api/swagger-ui.html](http://185.244.172.45:8090/api/swagger-ui.html)** | — |

---

## Возможности

- Совместная работа через списки задач (создание, вступление по паролю, роли ADMIN/USER)
- Приватные задачи, видимые только создателю
- Цвета пользователей для визуальной идентификации задач
- Отметка задач как выполненных с фиксацией даты и исполнителя
- REST API с JWT аутентификацией (React SPA, Android-клиент)
- BCrypt хэширование паролей
- CORS для поддержки React SPA и прямых API-запросов
- Автоверсионирование (MAJOR.MINOR.PATCH) с проверкой совместимости Android-клиента
- Оптимистичная блокировка (`@Version`) на всех Entity — защита от потерянных обновлений
- Защита от race conditions (TOCTOU) — атомарные операции через UNIQUE constraints
- Миграции БД через Liquibase (безопасно для существующих данных)
- Автоматический CI/CD через GitHub Actions
- Интерактивная документация API (Swagger UI / OpenAPI 3)
- 195 unit-тестов с проверкой покрытия (JaCoCo) + 3 нагрузочных теста (TestContainers, отдельный запуск)

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
| **Документация**| springdoc-openapi (Swagger UI)    | 2.8.6   |
| **CI/CD**       | GitHub Actions                    | -       |

---

## REST API

### Аутентификация

| Метод | Эндпоинт | Описание |
|-------|----------|----------|
| POST | `/api/auth/register` | Регистрация (возвращает JWT) |
| POST | `/api/auth/login` | Вход (возвращает JWT) |
| POST | `/api/auth/refresh` | Обновление access токена |

### Списки задач

| Метод | Эндпоинт | Описание |
|-------|----------|----------|
| POST | `/api/lists` | Создать список (роль ADMIN) |
| POST | `/api/lists/join` | Вступить в список по паролю |
| GET | `/api/lists` | Мои списки |
| GET | `/api/lists/{id}/members` | Участники списка |
| GET | `/api/lists/{id}/todos` | Задачи списка (с учётом приватности) |
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
| PUT | `/api/users/{id}` | Обновить пользователя |
| PUT | `/api/users/{id}/colors` | Обновить цвета задач |
| DELETE | `/api/users/{id}` | Удалить пользователя |

### Служебные

| Метод | Эндпоинт | Описание |
|-------|----------|----------|
| GET | `/api/status` | Статус, версия, min_android_version |
| GET | `/api/appName` | Название приложения |

Все эндпоинты (кроме auth, status, appName) требуют заголовок `Authorization: Bearer <token>`.

Интерактивная документация: **[Swagger UI](http://185.244.172.45:8090/api/swagger-ui.html)** (локально: `http://localhost:8090/api/swagger-ui.html`)

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
# Unit-тесты (195 тестов, без Docker)
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
- 195 unit-тестов + проверка покрытия JaCoCo (70% инструкций, 70% строк, 60% ветвлений, 80% методов)
- Нагрузочные тесты (3 шт.) запускаются отдельно: `mvn test -Pintegration` (требуют Docker)

**Этап 2 — Деплой** (только push в master):
- Сборка JAR + Docker-образ
- Push в Docker Hub (`mngerasimenko/todo-app`)
- Миграция БД через Liquibase (автоматически при старте приложения)
- Перезапуск контейнера через SSH
- Версия приложения: `APP_VERSION_MAJOR.APP_VERSION_MINOR.github.run_number` (автоинкремент PATCH)

Защита ветки master: обязательный PR + успешные тесты.

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
├── service/         Бизнес-логика (3 интерфейса + 3 реализации)
├── repository/      Spring Data JPA (4 репозитория)
├── model/           JPA-сущности (User, Todo, TaskList, TaskListUser, TaskListRole) + @Version
├── dto/             DTO + list/ + auth/ подпакеты
├── mapper/          Ручные мапперы (Todo, User, TaskList)
├── config/          OpenApiConfig (Swagger UI / OpenAPI)
├── security/        Spring Security + JWT (ApiSecurityConfig)
├── settings/        AppProperties (corsOrigins, версия, min_android_version)
├── exception/       GlobalExceptionHandler + кастомные исключения
└── TodolistApplication.java

src/main/resources/db/migration/   Liquibase-миграции (master + 5 changeset-файлов)
src/test/java/       195+ тестов (controller, service, repository, mapper, concurrency)
postman/             Postman-коллекция + окружения
```

---

## Связанные проекты

- **[todolist-web](https://github.com/mngerasimenko/todolist-web)** — React SPA (TypeScript + Vite + Tailwind CSS)
- **[todolist-android](https://github.com/mngerasimenko/todolist-android)** — нативный Android-клиент (Kotlin + Jetpack Compose)
