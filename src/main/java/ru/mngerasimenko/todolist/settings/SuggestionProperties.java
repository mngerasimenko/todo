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
     * Минимальная длина строки, которую кладём в словарь (track). Слова короче не храним —
     * 1-2 символа это мусор/опечатки, словарь должен содержать продукты (от 3 символов).
     */
    private int minTrackLength = 3;

    /**
     * Минимальная длина нормализованного префикса, по которому делаем suggest.
     * 1 — подсказываем с первой буквы (продукты часто короткие: сыр/чай/соль/лук). При меньшей
     * (пустой ввод) возвращаем пустой список без обращения в БД. Приватность держат minFreq +
     * PII-фильтры, а НЕ длина префикса (словарь публичный по замыслу), поэтому 1 безопасно.
     */
    private int minPrefixLength = 1;

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
