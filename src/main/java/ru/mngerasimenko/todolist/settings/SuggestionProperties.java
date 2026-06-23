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
     * Минимальное число РАЗНЫХ пользователей, при котором строка возвращается в подсказках
     * ({@code freq} теперь = distinct-счётчик авторов, а не вхождений). Это k-анонимность и
     * юридическое условие режима «обезличенных данных»: строку видно, только когда её ввели
     * не менее N разных людей. Старт 3 (на текущей базе ~26 MAU порог 5 оставит словарь почти
     * пустым); прицел вернуть 5 при росте до сотен MAU. Override через env
     * {@code APP_SUGGESTIONS_MIN_FREQ} (gate-чейн /ideas 2026-06-23).
     */
    private long minFreq = 3;

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
