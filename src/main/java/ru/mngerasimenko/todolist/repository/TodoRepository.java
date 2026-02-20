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
     * Возвращает все задачи аккаунта:
     * - публичные задачи (isPrivate = false)
     * - приватные задачи текущего пользователя (isPrivate = true AND user_id = userId)
     */
    @Query("SELECT t FROM Todo t WHERE t.account.id = :accountId AND (t.isPrivate = false OR (t.isPrivate = true AND t.user.id = :userId))")
    List<Todo> findByAccountIdVisibleToUser(@Param("accountId") Long accountId, @Param("userId") Long userId);

    /**
     * Возвращает только публичные задачи аккаунта.
     */
    List<Todo> findByAccountIdAndIsPrivateFalse(Long accountId);

    /**
     * Удалить приватные задачи пользователя в конкретном аккаунте.
     * Используется при выходе пользователя из аккаунта.
     */
    @Modifying
    @Query("DELETE FROM Todo t WHERE t.account.id = :accountId AND t.user.id = :userId AND t.isPrivate = true")
    void deletePrivateTodosByAccountIdAndUserId(@Param("accountId") Long accountId, @Param("userId") Long userId);
}
