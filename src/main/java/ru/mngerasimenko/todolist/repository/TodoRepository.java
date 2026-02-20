package ru.mngerasimenko.todolist.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.mngerasimenko.todolist.model.Todo;

import java.util.List;

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
     * Возвращает все задачи списка:
     * - публичные задачи (isPrivate = false)
     * - приватные задачи текущего пользователя (isPrivate = true AND user_id = userId)
     */
    @Query("SELECT t FROM Todo t WHERE t.taskList.id = :listId AND (t.isPrivate = false OR (t.isPrivate = true AND t.user.id = :userId))")
    List<Todo> findByListIdVisibleToUser(@Param("listId") Long listId, @Param("userId") Long userId);

    /**
     * Возвращает только публичные задачи списка.
     */
    List<Todo> findByListIdAndIsPrivateFalse(Long listId);

    /**
     * Удалить приватные задачи пользователя в конкретном списке.
     * Используется при выходе пользователя из списка.
     */
    @Modifying
    @Query("DELETE FROM Todo t WHERE t.taskList.id = :listId AND t.user.id = :userId AND t.isPrivate = true")
    void deletePrivateTodosByListIdAndUserId(@Param("listId") Long listId, @Param("userId") Long userId);
}
