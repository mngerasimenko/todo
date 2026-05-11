package ru.mngerasimenko.todolist.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.mngerasimenko.todolist.model.TaskList;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Репозиторий для работы со списками задач (таблица task_list).
 */
@Repository
public interface TaskListRepository extends JpaRepository<TaskList, Long> {

    /**
     * Удалить список по ID через JPQL (минуя persistence context).
     */
    @Modifying
    @Query("DELETE FROM TaskList t WHERE t.id = :listId")
    void deleteByListId(@Param("listId") Long listId);

    /**
     * Передать роль creator списка другому пользователю.
     * <p>
     * Вызывается из {@code UserServiceImpl.delete()} при удалении аккаунта
     * пользователя, который был создателем shared-списка с другими участниками
     * — иначе FK {@code fk_task_list_creator} блокирует удаление user-а.
     */
    @Modifying
    @Query("UPDATE TaskList t SET t.creator.id = :newCreatorId WHERE t.id = :listId")
    void updateCreator(@Param("listId") Long listId, @Param("newCreatorId") Long newCreatorId);

    long countByCreatedAtAfter(LocalDateTime since);

    /**
     * ID уникальных пользователей, создавших списки после указанной даты.
     * Используется для расчёта метрики «активные пользователи» в статистике.
     */
    @Query("SELECT DISTINCT tl.creator.id FROM TaskList tl WHERE tl.createdAt > :since")
    Set<Long> findActiveUserIdsSince(@Param("since") LocalDateTime since);
}
