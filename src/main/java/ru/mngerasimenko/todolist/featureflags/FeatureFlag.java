package ru.mngerasimenko.todolist.featureflags;

import java.util.Arrays;
import java.util.Optional;

/**
 * Реестр известных feature-флагов.
 * Новые флаги добавляются только здесь — через API нельзя создать неведомый флаг.
 *
 * Имя в {@link #name} используется одновременно как:
 * - ключ в URL: {@code PUT /api/admin/flags/{name}/{value}}
 * - ключ в Spring Environment (application.properties / env-переменные)
 */
public enum FeatureFlag {

    RATE_LIMIT("rate-limit.enabled", true, Audience.SERVER, OverrideLifetime.PROCESS,
            "Rate-limit через Bucket4j. При false API без ограничений на запросы " +
            "(не снимает защиту на nginx-уровне). Переключение живёт лишь до рестарта (как у " +
            "response-cache): снятая защита обязана восстановиться сама, даже если про неё " +
            "забыли. Нужно надолго — задавайте через env."),

    INACTIVE_REMINDER("app.inactive-reminder.enabled", true,
            "Scheduler ежедневной рассылки напоминаний неактивным пользователям (7+ дней). " +
            "При false scheduled-метод пропускает итерацию, сам бин остаётся живым."),

    ONBOARDING_REMINDER("app.onboarding-reminder.enabled", true,
            "Scheduler 3-дневного onboarding-напоминания (Phase 3.3). " +
            "Шлёт push + email пользователям, не возвращавшимся в приложение через 3 дня " +
            "после регистрации. Один раз на устройство (флаг onboarding_reminder_sent). " +
            "При false scheduled-метод пропускает итерацию, бин остаётся живым."),

    PUSH_NOTIFICATIONS("push-notifications.enabled", true,
            "Отправка push-уведомлений через Firebase при действиях в совместных списках " +
            "и inactive-reminder. Полезно выключить при нестабильной работе Firebase."),

    RESPONSE_CACHE("response-cache.enabled", true, Audience.SERVER, OverrideLifetime.PROCESS,
            "Кэширование ответов GET /api/users/me и GET /api/lists в Redis (TTL 60 сек). " +
            "При false — запросы идут напрямую в Postgres. Аварийное выключение при подозрении на " +
            "stale-данные. Переключение живёт до рестарта, как у rate-limit: кэш прикрывает в том " +
            "числе путь аутентификации, и забытое выключение навсегда оставило бы Postgres под " +
            "нагрузкой. Нужно надолго — через env."),

    SUGGESTIONS("app.suggestions.enabled", true,
            "Глобальный словарь подсказок при вводе задачи (Server R-6). " +
            "При false: GET /api/suggestions возвращает пустой список, хук в TodoServiceImpl " +
            "не пополняет словарь, cleanup-scheduler пропускает итерацию. " +
            "Эндпоинт остаётся доступен (без 404), чтобы старые клиенты не ломались. " +
            "НЕ влияет на личную историю списка — она живёт только на клиенте (см. " +
            "CLIENT_SUGGESTIONS_HISTORY)."),

    CLIENT_SUGGESTIONS_HISTORY("client.suggestions.history.enabled", true, Audience.CLIENT,
            "Личная история выполненных задач списка как источник подсказок (FR #5, Android 1.2.6). " +
            "При false приложение перестаёт и записывать завершения в локальную историю, и " +
            "показывать их — остаётся только глобальный словарь, то есть поведение до 1.2.6. " +
            "Уже накопленная история не удаляется. Выключать, если запись в Room на завершение " +
            "задачи начнёт мешать (перф) или подсказки из истории окажутся нежелательными."),

    CLIENT_SUGGESTIONS_DEDUP("client.suggestions.dedup.enabled", true, Audience.CLIENT,
            "Схлопывание подсказок, различающихся только эмодзи, регистром или краевой пунктуацией " +
            "(Android 1.2.6): одно слово занимает один слот выдачи. При false возвращается поведение " +
            "ДО фикса: точные совпадения по строгому ключу всё равно схлопываются, но «молоко» и " +
            "«молоко 🥛» снова показываются двумя строками. Выключать, если правило начнёт прятать " +
            "нужные подсказки. Действует со следующего холодного старта приложения."),

    TODO_REMINDERS("app.todo-reminders.enabled", false, Audience.SERVER, OverrideLifetime.PERSISTENT,
            "Рассылка напоминаний о сроках задач. Выключен по умолчанию: включается вручную после проверки на staging, потому что первый проход планировщика идёт по живым данным."),

    TODO_CREATE_DEDUPE("app.todo.create-dedupe.enabled", true,
            "Идемпотентность создания задачи по client_request_id. Клиент устроен как очередь " +
            "at-least-once: если ответ сервера до телефона не дошёл, он повторяет POST, и раньше " +
            "появлялась вторая строка (разбор инцидента 23.08.2026). При true сервер узнаёт повтор " +
            "по ключу, который клиент генерирует один раз на намерение, и возвращает уже созданную " +
            "задачу. Ложных схлопываний нет: два осознанных добавления одного текста несут разные " +
            "ключи. При false ключ не сохраняется вовсе — поведение возвращается к прежнему, " +
            "включая сам баг задвоения. Запросы без ключа (сборки до этой правки, веб) флаг " +
            "не затрагивает."),

    CLIENT_TODO_DUE_DATES("client.todo.due-dates.enabled", true, Audience.CLIENT,
            "Интерфейс сроков задач в Android-клиенте. Позволяет спрятать фичу на уже выпущенной сборке.");

    private final String name;
    private final boolean defaultValue;
    private final Audience audience;
    private final OverrideLifetime overrideLifetime;
    private final String description;

    FeatureFlag(String name, boolean defaultValue, String description) {
        this(name, defaultValue, Audience.SERVER, OverrideLifetime.PERSISTENT, description);
    }

    FeatureFlag(String name, boolean defaultValue, Audience audience, String description) {
        this(name, defaultValue, audience, OverrideLifetime.PERSISTENT, description);
    }

    FeatureFlag(String name, boolean defaultValue, Audience audience,
                OverrideLifetime overrideLifetime, String description) {
        this.name = name;
        this.defaultValue = defaultValue;
        this.audience = audience;
        this.overrideLifetime = overrideLifetime;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public boolean getDefaultValue() {
        return defaultValue;
    }

    public Audience getAudience() {
        return audience;
    }

    public OverrideLifetime getOverrideLifetime() {
        return overrideLifetime;
    }

    /** Переживает ли ручное переключение рестарт (то есть хранится ли оно в БД). */
    public boolean isOverridePersistent() {
        return overrideLifetime == OverrideLifetime.PERSISTENT;
    }

    /** Отдаётся ли флаг клиентскому приложению в {@code GET /api/status}. */
    public boolean isClientVisible() {
        return audience == Audience.CLIENT;
    }

    public String getDescription() {
        return description;
    }

    public static Optional<FeatureFlag> findByName(String name) {
        return Arrays.stream(values())
                .filter(f -> f.name.equals(name))
                .findFirst();
    }
}
