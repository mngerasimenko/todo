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

    PUSH_NOTIFICATIONS("push-notifications.enabled", true,
            "Отправка push-уведомлений через Firebase при действиях в совместных списках " +
            "и inactive-reminder. Полезно выключить при нестабильной работе Firebase.");

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
