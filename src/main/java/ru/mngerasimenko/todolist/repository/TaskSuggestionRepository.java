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
     * Шаг 1 distinct-учёта: гарантировать существование строки словаря БЕЗ инкремента частоты.
     * При первом появлении — INSERT с {@code freq=0} (ещё ни одного distinct-автора не зачтено);
     * при повторе — обновляем только {@code last_used_at} (freq не трогаем, им управляет
     * {@link #incrementFreq}). {@code text_display} на конфликте НЕ перезаписывается, чтобы
     * первое написание сохранялось.
     * <p>
     * Должен вызываться ДО {@link #addSuggestionUser}: FK {@code task_suggestion_user → task_suggestion(text)}
     * требует существующей строки (обе в одной REQUIRES_NEW-транзакции).
     */
    @Modifying
    @Query(value = """
            INSERT INTO task_suggestion (text, text_display, freq, last_used_at, blocked)
            VALUES (:text, :textDisplay, 0, NOW(), false)
            ON CONFLICT (text) DO UPDATE SET
                last_used_at = NOW()
            """, nativeQuery = true)
    void ensureSuggestion(@Param("text") String text,
                          @Param("textDisplay") String textDisplay);

    /**
     * Шаг 2 distinct-учёта: отметить, что данный автор ввёл данную строку.
     * Автор — псевдоним {@code userHash = HMAC(ключ, normalized + ':' + userId)} (per-text,
     * необратим к user_id). {@code ON CONFLICT DO NOTHING} — повторный ввод тем же автором
     * ничего не меняет. Возвращает 1, если это НОВЫЙ distinct-автор строки (строка реально
     * вставлена), иначе 0 — по этому значению сервис решает, звать ли {@link #incrementFreq}.
     * Так считаем именно РАЗНЫХ пользователей (k-анонимность), а не вхождения.
     */
    @Modifying
    @Query(value = """
            INSERT INTO task_suggestion_user (text, user_hash)
            VALUES (:text, :userHash)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int addSuggestionUser(@Param("text") String text,
                          @Param("userHash") String userHash);

    /**
     * Шаг 3 distinct-учёта: увеличить {@code freq} на 1. Вызывается сервисом ТОЛЬКО когда
     * {@link #addSuggestionUser} вернул 1 (появился новый distinct-автор). {@code freq = freq + 1}
     * атомарен (row lock), так что параллельные track разных юзеров считаются корректно.
     */
    @Modifying
    @Query(value = "UPDATE task_suggestion SET freq = freq + 1 WHERE text = :text",
            nativeQuery = true)
    void incrementFreq(@Param("text") String text);

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
     * Весь видимый словарь для bulk-выгрузки (Server R-7): {@code blocked = false} И
     * {@code freq >= :minFreq}. Порядок {@code freq DESC, text ASC} — детерминированный
     * (text — PK, уникален), чтобы ETag/контент-хеш на контроллере был стабилен между
     * вызовами при неизменных данных. Без prefix-фильтра: клиент кладёт результат в Room и
     * матчит локально. Объём мал (десятки строк), зовётся редко (синк ~1/сутки) — full scan ок.
     */
    @Query("""
            SELECT s FROM TaskSuggestion s
            WHERE s.blocked = false AND s.freq >= :minFreq
            ORDER BY s.freq DESC, s.text ASC
            """)
    List<TaskSuggestion> findAllVisible(@Param("minFreq") long minFreq);

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

    // ===== Reseed (seed 029): ре-агрегация distinct из прод-данных =====

    /**
     * Reseed-шаг: число НЕ заблокированных записей (для отчёта — сколько будет удалено
     * перед перестроением). Заблокированные admin'ом сохраняются и в счёт не идут.
     */
    @Query("SELECT COUNT(s) FROM TaskSuggestion s WHERE s.blocked = false")
    long countNonBlocked();

    /**
     * Reseed-шаг: нормализованные тексты всех заблокированных admin'ом записей.
     * Reseed их НЕ трогает (blocked сохраняется, строки не перестраиваются).
     */
    @Query("SELECT s.text FROM TaskSuggestion s WHERE s.blocked = true")
    List<String> findBlockedTexts();

    /**
     * Reseed-шаг 1: удалить все НЕ заблокированные записи перед перестроением.
     * FK {@code task_suggestion_user → task_suggestion(text) ON DELETE CASCADE} автоматически
     * чистит привязанных авторов. Заблокированные admin'ом строки (+ их авторы) остаются.
     * Выполняется в одной транзакции с reseed-вставками → нет окна пустого словаря для readers.
     */
    @Modifying
    @Query(value = "DELETE FROM task_suggestion WHERE blocked = false", nativeQuery = true)
    int deleteAllNonBlocked();

    /**
     * Reseed-шаг 2: записать строку словаря с ЯВНЫМ distinct-счётчиком {@code freq}
     * (= число разных авторов из агрегации, либо editorial-floor). После {@link #deleteAllNonBlocked}
     * конфликт возможен только с заблокированной строкой — но reseed их пропускает в коде,
     * поэтому {@code ON CONFLICT} здесь лишь страховка идемпотентности (не понижает freq повторного прогона).
     */
    @Modifying
    @Query(value = """
            INSERT INTO task_suggestion (text, text_display, freq, last_used_at, blocked)
            VALUES (:text, :textDisplay, :freq, NOW(), false)
            ON CONFLICT (text) DO UPDATE SET
                freq = EXCLUDED.freq,
                text_display = EXCLUDED.text_display,
                last_used_at = NOW()
            """, nativeQuery = true)
    void insertReseed(@Param("text") String text,
                      @Param("textDisplay") String textDisplay,
                      @Param("freq") long freq);

    /**
     * Reseed-шаг 0: попытаться взять транзакционный advisory-lock (сериализует прогоны reseed).
     * Возвращает {@code true}, если лок взят; {@code false}, если его уже держит другая транзакция
     * (значит reseed уже выполняется). Лок автоматически освобождается при commit/rollback —
     * поэтому вызывается ТОЛЬКО внутри {@code @Transactional}-метода reseed.
     */
    @Query(value = "SELECT pg_try_advisory_xact_lock(:key)", nativeQuery = true)
    boolean tryReseedAdvisoryLock(@Param("key") long key);
}
