package ru.mngerasimenko.todolist.settings;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Параметры глобального словаря подсказок (Server R-6).
 * <p>
 * Все значения настраиваются в {@code application.properties} (префикс {@code app.suggestions}).
 */
@Component
@ConfigurationProperties(prefix = "app.suggestions")
@Getter
@Setter
public class SuggestionProperties {

    /**
     * Минимальная частота, при которой строка возвращается в подсказках.
     * Защита от «1-разовых» опечаток / случайностей + от попытки ботнета протащить
     * чужую ПД (3 имитированных юзера = публикация). Поднят с 3 до 5 после panel-review
     * security#2, 2026-06-21.
     */
    private long minFreq = 5;

    /**
     * Максимальная длина текста, который попадает в словарь.
     * Длиннее — «предложение», а не «продукт» — пропускаем.
     * <p><b>Инвариант:</b> не поднимать без миграции {@code task_suggestion.text varchar(60)}.
     */
    private int maxTextLength = 30;

    /**
     * Минимальная длина нормализованного префикса, по которому стоит делать suggest.
     * При меньшей возвращаем пустой список без обращения в БД. Поднят с 2 до 3
     * после panel-review security#4 (anti-enumeration cost), 2026-06-21.
     */
    private int minPrefixLength = 3;

    /**
     * Максимальный {@code limit}, который клиент может запросить за один GET.
     * Запросы с большим limit обрезаются до этого значения.
     */
    private int maxLimit = 10;

    /**
     * Лимит по умолчанию, если клиент не передал {@code limit}.
     */
    private int defaultLimit = 5;

    /**
     * Сколько дней без использования допускается перед автоматическим удалением записи
     * в {@code SuggestionCleanupScheduler}.
     */
    private int cleanupDays = 365;
}
