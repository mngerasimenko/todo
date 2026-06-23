package ru.mngerasimenko.todolist.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Отчёт о ре-агрегации словаря подсказок (seed 029, Server R-6).
 * <p>
 * Содержит только агрегированные счётчики — никаких raw-данных пользователей (для dry-run
 * отдаётся наружу через admin-эндпоинт; raw-задачи / ключ прод не покидают).
 */
@Data
@Builder
public class SuggestionReseedReport {

    /** {@code true} — прогон без записи (только подсчёт что было бы сделано). */
    @JsonProperty("dry_run")
    private final boolean dryRun;

    /** Просканировано НЕ приватных задач. */
    @JsonProperty("todos_scanned")
    private final long todosScanned;

    /** Из них прошли цепочку track-фильтров (не email/телефон/мат/короткие/без букв). */
    @JsonProperty("todos_trackable")
    private final long todosTrackable;

    /** Уникальных нормализованных строк среди прошедших фильтры (любой distinct-счётчик). */
    @JsonProperty("distinct_products_total")
    private final long distinctProductsTotal;

    /** Строк, которые попадут в словарь (distinct-авторов ≥ minFreq, не заблокированы). */
    @JsonProperty("products_kept")
    private final long productsKept;

    /** Строк-авторов ({@code task_suggestion_user}), которые будут записаны для kept-строк. */
    @JsonProperty("contributor_rows_written")
    private final long contributorRowsWritten;

    /** Редакционных глаголов, добавленных floor'ом freq=minFreq (не всплывших из реальных данных). */
    @JsonProperty("editorial_verbs_floored")
    private final long editorialVerbsFloored;

    /** Заблокированных admin'ом строк — сохранены без изменений (reseed их не трогает). */
    @JsonProperty("blocked_preserved")
    private final long blockedPreserved;

    /** НЕ заблокированных строк, удалённых перед перестроением (occurrence-сид 026 + живые). */
    @JsonProperty("non_blocked_deleted")
    private final long nonBlockedDeleted;

    /** Применённый порог distinct-авторов (app.suggestions.min-freq на момент прогона). */
    @JsonProperty("min_freq_applied")
    private final long minFreqApplied;

    /** Топ-N итоговых строк по freq — для визуальной проверки (text + distinct-freq). */
    @JsonProperty("top_sample")
    private final List<TopEntry> topSample;

    /** Строка топ-сэмпла: нормализованный текст + итоговый distinct-счётчик. */
    public record TopEntry(String text, long freq) {
    }
}
