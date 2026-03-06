# TodoList API - Postman Collection

Коллекция для тестирования REST API Todo List с JWT аутентификацией.

## 📦 Содержимое

- `TodoList_API.postman_collection.json` — основная коллекция запросов
- `TodoList_Environment_Local.postman_environment.json` — переменные для локального окружения
- `TodoList_Environment_Production.postman_environment.json` — переменные для production

## 🚀 Быстрый старт

### 1. Импорт в Postman

1. Откройте Postman
2. Нажмите **Import** (верхний левый угол)
3. Перетащите файлы или выберите:
   - `TodoList_API.postman_collection.json`
   - `TodoList_Environment_Local.postman_environment.json`
   - `TodoList_Environment_Production.postman_environment.json`

### 2. Выбор окружения

В правом верхнем углу Postman выберите окружение:
- **TodoList - Local** — для локальной разработки (localhost:8090)
- **TodoList - Production** — для production сервера (todo.mngerasimenko.ru (HTTPS))

### 3. Порядок выполнения запросов

#### Шаг 1: Регистрация или вход

**Первый запуск (регистрация):**
```
Auth → Register
```

**Повторный запуск (вход):**
```
Auth → Login
```

✅ **JWT токены автоматически сохраняются** в переменные `accessToken` и `refreshToken`

#### Шаг 2: Работа с задачами

```
Todos → Create Todo
Todos → Get All Todos
Todos → Get Todo by ID
Todos → Update Todo
```

#### Шаг 3: Работа с пользователями

```
Users → Get All Users
Users → Get User by ID
Users → Create User
Users → Update User
```

## 🔑 Автоматическая обработка JWT

Коллекция автоматически:
- ✅ Сохраняет `access_token` после login/register
- ✅ Сохраняет `refresh_token` для обновления токена
- ✅ Сохраняет `userId` для последующих запросов
- ✅ Сохраняет `todoId` после создания задачи
- ✅ Добавляет JWT в заголовок `Authorization: Bearer <token>` для защищённых эндпоинтов

## 📋 Структура коллекции

### 1. Auth (Аутентификация)
- **POST** `/api/auth/register` — регистрация нового пользователя
- **POST** `/api/auth/login` — вход и получение JWT токенов
- **POST** `/api/auth/refresh` — обновление access токена

### 2. Todos (Задачи) 🔒
- **POST** `/api/todos/create` — создание задачи
- **GET** `/api/todos/all` — получение всех задач
- **GET** `/api/todos/{id}` — получение задачи по ID
- **GET** `/api/todos/user/{userId}` — задачи пользователя
- **PUT** `/api/todos/{id}` — обновление задачи

### 3. Users (Пользователи) 🔒
- **GET** `/api/users/all` — список пользователей
- **GET** `/api/users/{id}` — пользователь по ID
- **POST** `/api/users/create` — создание пользователя
- **PUT** `/api/users/{id}` — обновление пользователя
- **DELETE** `/api/users/{id}` — удаление пользователя

### 4. App (Служебные эндпоинты)
- **GET** `/api/status` — статус приложения
- **GET** `/api/appName` — название приложения

### 5. Negative Tests (Негативные тесты)
- Unauthorized - No Token (401)
- Invalid Credentials (401)
- Invalid Token (401)
- Validation Error (400)
- Not Found (404)

🔒 — требуют JWT токен

## 🧪 Автоматические тесты

Каждый запрос содержит автоматические тесты:

```javascript
// Пример тестов для Login
pm.test("Status code is 200", function () {
    pm.response.to.have.status(200);
});

pm.test("Response has access_token", function () {
    var jsonData = pm.response.json();
    pm.expect(jsonData.access_token).to.exist;

    // Автоматическое сохранение токена
    pm.collectionVariables.set("accessToken", jsonData.access_token);
});
```

## 📊 Запуск всей коллекции

### Через Postman Runner

1. Нажмите на коллекцию → **Run**
2. Выберите запросы для запуска
3. Нажмите **Run TodoList API - JWT Auth**

### Через Newman (CLI)

```bash
# Установка Newman
npm install -g newman

# Запуск коллекции (локально)
newman run TodoList_API.postman_collection.json \
  -e TodoList_Environment_Local.postman_environment.json

# Запуск с отчётом
newman run TodoList_API.postman_collection.json \
  -e TodoList_Environment_Local.postman_environment.json \
  -r html,cli \
  --reporter-html-export report.html
```

## 🔧 Переменные

### Collection Variables (автоматически заполняются)

| Переменная | Описание | Автозаполнение |
|------------|----------|----------------|
| `accessToken` | JWT access токен | ✅ После login/register |
| `refreshToken` | JWT refresh токен | ✅ После login/register |
| `userId` | ID пользователя | ✅ После login/register |
| `todoId` | ID задачи | ✅ После создания задачи |

### Environment Variables

| Переменная | Local | Production |
|------------|-------|------------|
| `baseUrl` | http://localhost:8090 | http://todo.mngerasimenko.ru (HTTPS) |

## ⚙️ Настройка для своего сервера

Если у вас другой URL сервера:

1. Откройте окружение (Environment)
2. Измените значение `baseUrl`
3. Сохраните

## 🐛 Устранение неполадок

### "401 Unauthorized" на защищённых эндпоинтах

**Причина:** JWT токен отсутствует или истёк

**Решение:**
1. Выполните `Auth → Login`
2. Проверьте, что переменная `accessToken` заполнена
3. Если токен истёк (1 час), используйте `Auth → Refresh Token`

### "400 Bad Request" при создании задачи/пользователя

**Причина:** Невалидные данные или дубликат

**Решение:**
- Проверьте обязательные поля
- Используйте уникальные email/name для пользователей

### "404 Not Found"

**Причина:** Объект не существует

**Решение:**
- Проверьте, что `userId` или `todoId` корректны
- Сначала создайте объект, затем используйте его

## 📖 Дополнительная документация

- API документация: `.claude/docs/api.md`
- Архитектура: `.claude/docs/architecture.md`
- Deployment: `.claude/docs/deployment.md`

## 🔐 Безопасность

⚠️ **Важно:**
- Не коммитьте файлы окружения с реальными токенами в Git!
- В production используйте HTTPS
- JWT_SECRET должен храниться в переменных окружения

## 📝 Примеры запросов

### Регистрация пользователя

POST /api/auth/register
```json
{
  "email": "user@example.com",
  "name": "testUser",
  "password": "12345"
}
```

**Ответ:**
```json
{
  "access_token": "eyJhbGci...",
  "refresh_token": "eyJhbGci...",
  "expires_in": 3600,
  "token_type": "Bearer",
  "user": {
    "id": 1,
    "email": "user@example.com",
    "name": "testUser"
  }
}
```

### Создание задачи

POST /api/todos/create
Authorization: Bearer <access_token>
```json
{
  "name": "Купить молоко",
  "user_id": 1
}
```

**Ответ:**
```json
{
  "id": 1,
  "name": "Купить молоко",
  "date_time": "2026-02-17T10:30:00",
  "done": false,
  "user_id": 1,
  "user_name": "testUser",
  "created_at": "2026-02-17T10:30:00"
}
```

## 🤝 Поддержка

Если у вас возникли проблемы:
1. Проверьте, что сервер запущен
2. Проверьте консоль Postman (View → Show Postman Console)
3. Проверьте логи сервера

---

**Версия коллекции:** 1.0
**Последнее обновление:** 2026-02-17
**API версия:** Spring Boot 3.5.6 + JWT
