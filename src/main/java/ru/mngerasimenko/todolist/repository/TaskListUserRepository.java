package ru.mngerasimenko.todolist.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.mngerasimenko.todolist.model.TaskListRole;
import ru.mngerasimenko.todolist.model.TaskListUser;
import ru.mngerasimenko.todolist.model.TaskListUserId;

import java.util.List;
import java.util.Optional;

/**
 * Репозиторий для работы со связью пользователей и списков (таблица task_list_user).
 */
@Repository
public interface TaskListUserRepository extends JpaRepository<TaskListUser, TaskListUserId> {

    /**
     * Загружает списки пользователя вместе с создателем списка (JOIN FETCH).
     * Избегает N+1 запросов при обращении к taskList.creator.name в маппере.
     */
    @EntityGraph(attributePaths = {"taskList", "taskList.creator"})
    List<TaskListUser> findByUserId(Long userId);

    boolean existsByIdListIdAndIdUserId(Long listId, Long userId);

    /**
     * Возвращает ID всех списков, в которых состоит пользователь.
     */
    @Query("SELECT tlu.taskList.id FROM TaskListUser tlu WHERE tlu.user.id = :userId")
    List<Long> findListIdsByUserId(@Param("userId") Long userId);

    boolean existsByIdListIdAndRole(Long listId, TaskListRole role);

    /**
     * Возвращает первого администратора списка (для проверки лимитов подписки).
     */
    Optional<TaskListUser> findFirstByIdListIdAndRole(Long listId, TaskListRole role);

    Optional<TaskListUser> findByIdListIdAndIdUserId(Long listId, Long userId);

    /**
     * Загружает участников списка вместе с данными пользователей (JOIN FETCH).
     * Избегает N+1 запросов при обращении к user.name в маппере.
     */
    @EntityGraph(attributePaths = {"user"})
    List<TaskListUser> findByIdListId(Long listId);

    /**
     * Количество списков, в которых состоит пользователь.
     */
    @Query("SELECT COUNT(tlu) FROM TaskListUser tlu WHERE tlu.user.id = :userId")
    long countByUserId(@Param("userId") Long userId);

    /**
     * Количество участников в списке.
     */
    @Query("SELECT COUNT(tlu) FROM TaskListUser tlu WHERE tlu.taskList.id = :listId")
    long countByListId(@Param("listId") Long listId);

    /**
     * Среднее количество участников в списке.
     */
    @Query(value = "SELECT COALESCE(AVG(cnt), 0) FROM (SELECT COUNT(*) AS cnt FROM task_list_user GROUP BY list_id) sub", nativeQuery = true)
    double avgMembersPerList();

    /**
     * Количество совместных списков (больше 1 участника).
     */
    @Query(value = "SELECT COUNT(*) FROM (SELECT list_id FROM task_list_user GROUP BY list_id HAVING COUNT(*) > 1) sub", nativeQuery = true)
    long countSharedLists();

    /**
     * Удалить запись участия пользователя в списке.
     */
    @Modifying
    @Query("DELETE FROM TaskListUser tlu WHERE tlu.taskList.id = :listId AND tlu.user.id = :userId")
    void deleteByListIdAndUserId(@Param("listId") Long listId, @Param("userId") Long userId);

    /**
     * Удалить всех участников списка. Используется при удалении списка администратором.
     */
    @Modifying
    @Query("DELETE FROM TaskListUser tlu WHERE tlu.taskList.id = :listId")
    void deleteByListId(@Param("listId") Long listId);
}
