package ru.mngerasimenko.todolist.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.mngerasimenko.todolist.model.Todo;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Репозиторий для работы с задачами (таблица todo).
 */
@Repository
public interface TodoRepository extends JpaRepository<Todo, Long> {

    List<Todo> findAllByUserId(long userId);

    List<Todo> findAllByUserIdAndNameContainingIgnoreCase(long userId, String title);

    Todo findByName(String title);

    List<Todo> findAllByUserIdAndDoneOrderByIdDesc(long userId, boolean done);

    Todo findByIdAndUserId(long id, long userId);

    @Modifying
    void deleteByUserIdAndId(long userId, long todoId);

    List<Todo> findByUserId(Long userId);

    List<Todo> findByUserIdAndDone(Long userId, boolean done);

    void deleteByUserId(Long userId);

    /**
     * Возвращает задачи списка, видимые пользователю:
     * - публичные задачи (isPrivate = false)
     * - приватные задачи текущего пользователя (isPrivate = true AND user_id = userId)
     * JOIN FETCH t.user и LEFT JOIN FETCH t.completorUser — устраняют N+1 запросы при маппинге.
     */
    @Query("SELECT t FROM Todo t JOIN FETCH t.user LEFT JOIN FETCH t.completorUser WHERE t.taskList.id = :listId AND (t.isPrivate = false OR (t.isPrivate = true AND t.user.id = :userId))")
    List<Todo> findByListIdVisibleToUser(@Param("listId") Long listId, @Param("userId") Long userId);

    /**
     * Возвращает только публичные задачи списка.
     */
    List<Todo> findByListIdAndIsPrivateFalse(Long listId);

    /**
     * Возвращает задачи по id, отфильтрованные по конкретному listId.
     * Используется в bulk-reorder для проверки, что все переданные id принадлежат указанному списку
     * (PATCH /api/lists/{id}/todos/reorder).
     */
    List<Todo> findByIdInAndListId(List<Long> ids, Long listId);

    /**
     * Возвращает задачи из указанных списков, видимые пользователю:
     * - публичные задачи (isPrivate = false)
     * - приватные задачи текущего пользователя (isPrivate = true AND user_id = userId)
     */
    @Query("SELECT t FROM Todo t JOIN FETCH t.user LEFT JOIN FETCH t.completorUser " +
            "WHERE t.taskList.id IN :listIds AND (t.isPrivate = false OR (t.isPrivate = true AND t.user.id = :userId))")
    List<Todo> findByListIdsVisibleToUser(@Param("listIds") List<Long> listIds, @Param("userId") Long userId);

    /**
     * Количество задач в списке.
     */
    @Query("SELECT COUNT(t) FROM Todo t WHERE t.taskList.id = :listId")
    long countByListId(@Param("listId") Long listId);

    /**
     * Удалить приватные задачи пользователя в конкретном списке.
     * Используется при выходе пользователя из списка.
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM Todo t WHERE t.taskList.id = :listId AND t.user.id = :userId AND t.isPrivate = true")
    void deletePrivateTodosByListIdAndUserId(@Param("listId") Long listId, @Param("userId") Long userId);

    /**
     * Удалить все задачи списка. Используется при удалении списка администратором.
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM Todo t WHERE t.taskList.id = :listId")
    void deleteByListId(@Param("listId") Long listId);

    /**
     * Перенести публичные задачи пользователя на системного «Удалённый пользователь».
     * Используется при удалении аккаунта.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Todo t SET t.user.id = :newUserId WHERE t.user.id = :oldUserId")
    void reassignUser(@Param("oldUserId") Long oldUserId, @Param("newUserId") Long newUserId);

    /**
     * Перенести completor_user на системного «Удалённый пользователь».
     * Используется при удалении аккаунта.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Todo t SET t.completorUser.id = :newUserId WHERE t.completorUser.id = :oldUserId")
    void reassignCompletorUser(@Param("oldUserId") Long oldUserId, @Param("newUserId") Long newUserId);

    /**
     * Количество приватных задач.
     */
    long countByIsPrivateTrue();

    long countByCreatedAtAfter(LocalDateTime since);

    long countByDoneTrue();

    long countByCompletedAtAfter(LocalDateTime since);

    /**
     * ID уникальных пользователей, создавших или выполнивших задачи после указанной даты.
     * Используется для расчёта метрики «активные пользователи» в статистике.
     */
    @Query("SELECT DISTINCT t.user.id FROM Todo t WHERE t.createdAt > :since OR t.completedAt > :since")
    Set<Long> findActiveUserIdsSince(@Param("since") LocalDateTime since);

    /**
     * НЕ приватные задачи keyset-пагинацией (id ASC, id &gt; afterId) — источник distinct-агрегации
     * словаря (seed 029). Keyset (а не OFFSET) устойчив к конкурентным вставкам во время скана:
     * новые задачи получают больший id и попадают в последующие батчи, строки не перескакивают
     * границу страницы. Поле {@code name} расшифровывается прозрачно через {@code EncryptedStringConverter},
     * поэтому distinct по plaintext считаем в Java (в SQL заголовки зашифрованы). Лимит батча — через
     * {@link Pageable} (без сортировки; порядок задаёт {@code ORDER BY} запроса).
     */
    @Query("SELECT t FROM Todo t WHERE t.isPrivate = false AND t.id > :afterId ORDER BY t.id ASC")
    List<Todo> findNonPrivateForReseed(@Param("afterId") long afterId, Pageable pageable);

    /**
     * Созревшие для рассылки задачи. Момент отправки считается прямо в SQL:
     * дата и время срока истолковываются в поясе задачи, из результата вычитается запас.
     * Нижняя граница (staleBefore) отсекает протухшие: без неё создание задачи
     * с прошлой датой или переезд старых данных вызвали бы веерную рассылку.
     */
    @Query(value = """
            SELECT * FROM todo t
            WHERE t.done = false
              AND t.due_date IS NOT NULL
              AND t.reminder_sent_at IS NULL
              AND ((t.due_date + t.due_time) AT TIME ZONE COALESCE(t.due_timezone, 'Europe/Moscow'))
                  - make_interval(mins => t.remind_before_minutes) <= :now
              AND ((t.due_date + t.due_time) AT TIME ZONE COALESCE(t.due_timezone, 'Europe/Moscow'))
                  - make_interval(mins => t.remind_before_minutes) > :staleBefore
            ORDER BY t.due_date, t.due_time
            """, nativeQuery = true)
    List<Todo> findDueForReminder(@Param("now") Instant now, @Param("staleBefore") Instant staleBefore);

    /**
     * Задачи со сроком, видимые пользователю (экран «Сегодня»): невыполненные, срок не
     * позже верхней границы (сегодня + горизонт «Дальше»), из списков пользователя,
     * приватные — только его собственные. Видимость строится так же, как в
     * {@link #findByListIdVisibleToUser}.
     */
    @Query("""
            SELECT t FROM Todo t
            JOIN FETCH t.user
            LEFT JOIN FETCH t.completorUser
            WHERE t.done = false
              AND t.dueDate IS NOT NULL
              AND t.dueDate <= :until
              AND t.taskList.id IN (
                  SELECT tlu.id.listId FROM TaskListUser tlu WHERE tlu.id.userId = :userId
              )
              AND (t.isPrivate = false OR t.user.id = :userId)
            ORDER BY t.dueDate, t.dueTime
            """)
    List<Todo> findWithDueVisibleToUser(@Param("userId") Long userId, @Param("until") LocalDate until);

    /**
     * Отметка об отправке ставится точечным UPDATE мимо сущности: у Todo есть @Version,
     * и запись через entity ловила бы конфликт всякий раз, когда пользователь
     * редактирует задачу в момент прохода планировщика.
     * <p>
     * REQUIRES_NEW — намеренно: вызывающий (TodoServiceImpl.dispatchDueReminders) не держит
     * одну транзакцию на весь свип (panel-review Task 8, Critical: иначе упавший UPDATE или
     * сбой коммита откатывал бы уже сделанные отметки предыдущих задач того же прохода).
     * Отметка каждой задачи коммитится независимо, вне зависимости от транзакции вызывающего.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    @Query(value = "UPDATE todo SET reminder_sent_at = :sentAt WHERE id = :todoId", nativeQuery = true)
    void markReminderSent(@Param("todoId") Long todoId, @Param("sentAt") LocalDateTime sentAt);
}
