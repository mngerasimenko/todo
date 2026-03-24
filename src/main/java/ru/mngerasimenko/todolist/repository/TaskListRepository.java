package ru.mngerasimenko.todolist.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.mngerasimenko.todolist.model.TaskList;

import java.time.LocalDateTime;

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

    long countByCreatedAtAfter(LocalDateTime since);
}
