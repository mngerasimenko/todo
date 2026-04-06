package ru.mngerasimenko.todolist.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.mngerasimenko.todolist.model.RefreshToken;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Репозиторий для работы с refresh-токенами (таблица refresh_token).
 */
@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Отзывает все токены в семье (reuse detection).
     */
    @Modifying
    @Query("UPDATE RefreshToken r SET r.revoked = true WHERE r.familyId = :familyId")
    void revokeFamily(@Param("familyId") UUID familyId);

    /**
     * Находит активный (не отозванный, не истёкший) токен в семье.
     * Используется для обработки конкурентных refresh-запросов:
     * если клиент повторно отправил старый токен, но ротация уже прошла —
     * ротируем активный токен вместо блокировки всей семьи.
     */
    @Query("SELECT r FROM RefreshToken r WHERE r.familyId = :familyId " +
            "AND r.revoked = false AND r.expiresAt > :now")
    Optional<RefreshToken> findActiveFamilyToken(
            @Param("familyId") UUID familyId,
            @Param("now") LocalDateTime now);

    /**
     * Удаляет все истёкшие токены.
     */
    @Modifying
    @Query("DELETE FROM RefreshToken r WHERE r.expiresAt < :now")
    void deleteExpired(@Param("now") LocalDateTime now);

    /**
     * Удаляет все токены пользователя (при logout all / удалении аккаунта).
     */
    @Modifying
    @Query("DELETE FROM RefreshToken r WHERE r.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
