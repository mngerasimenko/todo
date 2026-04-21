package ru.mngerasimenko.todolist.featureflags;

/**
 * Источник текущего значения feature-флага (для диагностики в GET /api/admin/flags).
 */
public enum FlagSource {
    /** Установлено админом через {@code PUT /api/admin/flags/{name}/{value}}. */
    RUNTIME,
    /** Задано в application.properties или env-переменной при старте процесса. */
    ENV,
    /** Никто не переопределял — используется hard-coded default из {@link FeatureFlag}. */
    DEFAULT
}
