package ru.mngerasimenko.todolist.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.mngerasimenko.todolist.model.TaskSuggestion;

import java.util.List;

/**
 * Репозиторий глобального словаря подсказок (Server R-6).
 * <p>
 * Чтение — JPQL для prefix-search и сортировки по частоте.
 * Запись — native PostgreSQL UPSERT (атомарный {@code ON CONFLICT}, как
 * {@link PushTokenRepository#upsertByDeviceId} — выбран после инцидента с гонкой 2026-06-07).
 */
public interface TaskSuggestionRepository extends JpaRepository<TaskSuggestion, String> {

    /**
     * Атомарный UPSERT записи в словарь. При первом появлении строки — INSERT с {@code freq=1};
     * при повторе — {@code freq = freq + 1} и {@code last_used_at = NOW()}.
     * {@code text_display} НЕ перезаписывается на конфликте, чтобы первое введённое
     * пользователем оригинальное написание не подменялось на следующее.
     */
    @Modifying
    @Query(value = """
            INSERT INTO task_suggestion (text, text_display, freq, last_used_at, blocked)
            VALUES (:text, :textDisplay, 1, NOW(), false)
            ON CONFLICT (text) DO UPDATE SET
                freq = task_suggestion.freq + 1,
                last_used_at = NOW()
            """, nativeQuery = true)
    void upsert(@Param("text") String text,
                @Param("textDisplay") String textDisplay);

    /**
     * Топ-N подсказок по prefix, отсортированных по убыванию частоты,
     * с порогом {@code freq >= :minFreq} (фильтрует «1-разовые» случайности)
     * и {@code blocked = false}.
     * <p>
     * <b>Важно:</b> сервис должен передавать <i>уже готовый</i> LIKE-pattern
     * с {@code '%'} на конце (например {@code "хле%"}), а не сырой prefix.
     * Изначально здесь был {@code LIKE CONCAT(:prefix, '%')}, но при generic-plan
     * PostgreSQL не доказывает «const prefix» на этапе PLAN и не использует индекс
     * {@code idx_task_suggestion_prefix (text varchar_pattern_ops)} — уходит в Seq Scan.
     * Один bind-параметр без CONCAT/||-конкатенации решает проблему (panel-review
     * performance#2, 2026-06-21).
     */
    @Query("""
            SELECT s FROM TaskSuggestion s
            WHERE s.text LIKE :prefixPattern ESCAPE '\\'
              AND s.freq >= :minFreq
              AND s.blocked = false
            ORDER BY s.freq DESC, s.lastUsedAt DESC
            """)
    List<TaskSuggestion> findTopByPrefix(@Param("prefixPattern") String prefixPattern,
                                         @Param("minFreq") long minFreq,
                                         org.springframework.data.domain.Pageable pageable);

    /**
     * Установить {@code blocked = true} для конкретной нормализованной строки.
     * Возвращает число затронутых строк (0 если такой строки нет).
     */
    @Modifying
    @Query("UPDATE TaskSuggestion s SET s.blocked = true WHERE s.text = :text")
    int block(@Param("text") String text);

    /**
     * Удалить записи, не использовавшиеся более {@code cutoffDays} дней.
     * Защита от бесконечного роста таблицы (cleanup-scheduler раз в неделю).
     */
    @Modifying
    @Query(value = """
            DELETE FROM task_suggestion
            WHERE last_used_at < NOW() - make_interval(days => :cutoffDays)
            """, nativeQuery = true)
    int deleteOlderThanDays(@Param("cutoffDays") int cutoffDays);
}
