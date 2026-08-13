package ru.mngerasimenko.todolist.featureflags;

/**
 * Источник текущего значения feature-флага (для диагностики в GET /api/admin/flags).
 */
public enum FlagSource {
    /** Установлено админом через {@code PUT /api/admin/flags/{name}/{value}}, но НЕ сохранено:
     *  либо флаг процессный (защита), либо запись в БД не удалась. Слетит на ближайшем рестарте. */
    RUNTIME,
    /** Установлено админом и сохранено в БД: переживёт рестарт, деплой и пересоздание контейнеров.
     *  Отличать от {@link #RUNTIME} важно во время инцидента — видно, вернётся ли фича сама. */
    PERSISTED,
    /** Задано в application.properties или env-переменной при старте процесса. */
    ENV,
    /** Никто не переопределял — используется hard-coded default из {@link FeatureFlag}. */
    DEFAULT
}
