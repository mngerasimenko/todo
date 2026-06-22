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

    RATE_LIMIT("rate-limit.enabled", true,
            "Rate-limit через Bucket4j. При false API без ограничений на запросы " +
            "(не снимает защиту на nginx-уровне). Рестарт контейнера сбрасывает runtime-override."),

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

    RESPONSE_CACHE("response-cache.enabled", true,
            "Кэширование ответов GET /api/users/me и GET /api/lists в Redis (TTL 60 сек). " +
            "При false — запросы идут напрямую в Postgres. Аварийное выключение при подозрении на stale-данные."),

    SUGGESTIONS("app.suggestions.enabled", true,
            "Глобальный словарь подсказок при вводе задачи (Server R-6). " +
            "При false: GET /api/suggestions возвращает пустой список, хук в TodoServiceImpl " +
            "не пополняет словарь, cleanup-scheduler пропускает итерацию. " +
            "Эндпоинт остаётся доступен (без 404), чтобы старые клиенты не ломались.");

    private final String name;
    private final boolean defaultValue;
    private final String description;

    FeatureFlag(String name, boolean defaultValue, String description) {
        this.name = name;
        this.defaultValue = defaultValue;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public boolean getDefaultValue() {
        return defaultValue;
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
