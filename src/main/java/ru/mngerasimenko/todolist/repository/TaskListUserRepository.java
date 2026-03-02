package ru.mngerasimenko.todolist.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.mngerasimenko.todolist.model.TaskListUser;
import ru.mngerasimenko.todolist.model.TaskListUserId;

import java.util.List;
import java.util.Optional;

/**
 * Репозиторий для работы со связью пользователей и списков (таблица task_list_user).
 */
@Repository
public interface TaskListUserRepository extends JpaRepository<TaskListUser, TaskListUserId> {

    List<TaskListUser> findByUserId(Long userId);

    boolean existsByIdListIdAndIdUserId(Long listId, Long userId);

    Optional<TaskListUser> findByIdListIdAndIdUserId(Long listId, Long userId);

    /**
     * Загружает участников списка вместе с данными пользователей (JOIN FETCH).
     * Избегает N+1 запросов при обращении к user.name в маппере.
     */
    @EntityGraph(attributePaths = {"user"})
    List<TaskListUser> findByIdListId(Long listId);

    /**
     * Удалить запись участия пользователя в списке.
     */
    @Modifying
    @Query("DELETE FROM TaskListUser tlu WHERE tlu.taskList.id = :listId AND tlu.user.id = :userId")
    void deleteByListIdAndUserId(@Param("listId") Long listId, @Param("userId") Long userId);
}
