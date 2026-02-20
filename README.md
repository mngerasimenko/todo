# Todo List

![Java](https://img.shields.io/badge/Java-17-007396?logo=java)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5.6-6DB33F?logo=spring)
![Vaadin](https://img.shields.io/badge/Vaadin-24.9.10-2D3E50?logo=vaadin)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15-336791?logo=postgresql)
![Docker](https://img.shields.io/badge/Docker-24+-2496ED?logo=docker)
![GitHub Actions](https://img.shields.io/badge/GitHub_Actions-CI%2FCD-2088FF?logo=github-actions)

Веб-приложение для совместного управления списком задач. Система списков задач позволяет нескольким пользователям работать в общем пространстве, видеть задачи друг друга и отмечать их выполнение.

---

## Demo

Приложение развернуто и доступно по адресу:

**[Попробовать онлайн](http://185.244.172.45:8090)**
Логин: `testUser` | Пароль: `testUser`

---

## Возможности

- Совместная работа через списки задач (создание, вступление по паролю, роли ADMIN/USER)
- Приватные задачи, видимые только создателю
- Цвета пользователей для визуальной идентификации задач
- Отметка задач как выполненных с фиксацией даты и исполнителя
- JWT аутентификация для REST API + session-based для Vaadin UI
- BCrypt хэширование паролей
- Фильтрация и поиск задач
- Кастомная Vaadin тема с адаптивным дизайном
- Автоматический CI/CD через GitHub Actions
- 208 тестов с проверкой покрытия (JaCoCo)

---

## Технологический стек

| Категория       | Технология                        | Версия  |
|-----------------|-----------------------------------|---------|
| **Язык**        | Java                              | 17      |
| **Backend**     | Spring Boot                       | 3.5.6   |
| **UI**          | Vaadin                            | 24.9.10 |
| **БД**          | PostgreSQL                        | 17      |
| **Безопасность**| Spring Security + JWT (jjwt)      | 6.4.6   |
| **Сборка**      | Maven                             | 3.9     |
| **Тесты**       | JUnit 5 + Mockito + AssertJ       | -       |
| **Покрытие**    | JaCoCo                            | 0.8.14  |
| **Контейнеры**  | Docker + Docker Compose           | 24+     |
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
| DELETE | `/api/todos/{id}` | Удалить задачу |

### Пользователи

| Метод | Эндпоинт | Описание |
|-------|----------|----------|
| POST | `/api/users/create` | Создать пользователя |
| GET | `/api/users/all` | Все пользователи |
| GET | `/api/users/{id}` | Пользователь по ID |
| PUT | `/api/users/{id}` | Обновить пользователя |
| PUT | `/api/users/{id}/colors` | Обновить цвета задач |
| DELETE | `/api/users/{id}` | Удалить пользователя |

Все эндпоинты (кроме auth, status, appName) требуют заголовок `Authorization: Bearer <token>`.

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
docker compose up -d --build
docker compose ps
docker compose logs -f todo-app
docker compose down
```

### Тесты

```bash
# Все тесты
mvn test

# С отчётом покрытия
mvn test jacoco:report
```

---

## CI/CD

Проект использует пайплайн `.github/workflows/deploy.yml`:

**Этап 1 — Тесты** (все PR и push в master):
- 208 тестов + проверка покрытия JaCoCo (70% инструкций, 70% строк, 60% ветвлений, 80% методов)

**Этап 2 — Деплой** (только push в master):
- Сборка JAR + Docker-образ
- Push в Docker Hub (`mngerasimenko/todo-app`)
- SQL-миграция БД на сервере (идемпотентная)
- Перезапуск контейнера через SSH

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
├── service/         Бизнес-логика (3 интерфейса + 4 реализации)
├── repository/      Spring Data JPA (5 репозиториев)
├── model/           JPA-сущности (User, Todo, TaskList, TaskListUser, TaskListRole)
├── dto/             DTO + list/ + auth/ подпакеты
├── mapper/          Ручные мапперы (Todo, User, TaskList)
├── security/        Spring Security + JWT (2 цепочки: API + Vaadin)
├── view/            Vaadin UI (Login, Main, List, TodoForm)
├── exception/       GlobalExceptionHandler + кастомные исключения
└── TodolistApplication.java

src/test/java/       208 тестов (controller, service, repository, mapper)
frontend/themes/     Кастомная Vaadin тема
postman/             Postman-коллекция + окружения
```

---

## Android-приложение

Нативное Android-приложение для этого сервера (Kotlin + Jetpack Compose):

**[todolist-android](https://github.com/mngerasimenko/todolist-android)**
