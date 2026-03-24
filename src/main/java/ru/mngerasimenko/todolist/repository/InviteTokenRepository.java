package ru.mngerasimenko.todolist.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.mngerasimenko.todolist.model.InviteToken;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Репозиторий для работы с токенами приглашений (таблица invite_token).
 */
@Repository
public interface InviteTokenRepository extends JpaRepository<InviteToken, Long> {

    /**
     * Загружает invite-токен вместе со списком, создателем списка и инвайтером (JOIN FETCH).
     * Избегает N+1 при обращении к taskList.creator.name и inviter.name.
     */
    @EntityGraph(attributePaths = {"taskList", "taskList.creator", "inviter"})
    Optional<InviteToken> findByTokenHash(String tokenHash);

    /**
     * Удалить все истёкшие токены.
     */
    @Modifying
    @Query("DELETE FROM InviteToken t WHERE t.expiresAt < :now")
    void deleteExpired(@Param("now") LocalDateTime now);

    /**
     * Удалить все токены приглашений для указанного списка.
     */
    @Modifying
    @Query("DELETE FROM InviteToken t WHERE t.taskList.id = :listId")
    void deleteByListId(@Param("listId") Long listId);

    long countByExpiresAtAfter(LocalDateTime now);
}
