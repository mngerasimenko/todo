package ru.mngerasimenko.todolist.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.mngerasimenko.todolist.model.User;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Репозиторий для работы с пользователями (таблица todo_users).
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /** Поиск по blind index (HMAC-SHA256 хеш email) */
    User findByEmailHash(String emailHash);

    User getUserById(Long id);

    User getUserByAuthId(String authId);

    User findByEmailVerificationToken(String tokenHash);

    User findByPasswordResetToken(String tokenHash);

    long countByCreatedAtAfter(LocalDateTime since);

    List<User> findByCreatedAtAfterOrderByCreatedAtDesc(LocalDateTime since);

    long countByEmailVerifiedTrue();

    /**
     * Обновить время последней активности пользователя.
     * Прямой UPDATE без загрузки Entity, не инкрементирует @Version.
     */
    /**
     * Обновить время последней активности и сбросить счётчик напоминаний.
     */
    @Modifying
    @Query("UPDATE User u SET u.lastActiveAt = :time, u.reminderCount = 0, u.lastReminderSentAt = NULL WHERE u.id = :userId")
    void updateLastActiveAt(@Param("userId") Long userId, @Param("time") LocalDateTime time);

    /**
     * Найти пользователей, неактивных с указанной даты, которым отправлено менее maxReminders напоминаний.
     * Фильтр reminderOptOut=false добавлен 2026-05-17 (forward-looking opt-out, Phase 3.3 unsubscribe).
     */
    @Query("SELECT u FROM User u WHERE u.id > 0 " +
            "AND (u.lastActiveAt IS NULL OR u.lastActiveAt < :inactiveSince) " +
            "AND u.reminderCount < :maxReminders " +
            "AND u.reminderOptOut = false")
    List<User> findInactiveUsersForReminder(
            @Param("inactiveSince") LocalDateTime inactiveSince,
            @Param("maxReminders") int maxReminders);

    /**
     * Найти пользователя по unsubscribe-токену (одноразовый токен из email-footer).
     * Используется на GET /api/users/unsubscribe-reminder?token=...
     */
    User findByUnsubscribeToken(String unsubscribeToken);

    /**
     * Кандидаты на 3-дневное onboarding-напоминание (Phase 3.3).
     * Условия:
     *   - не системный юзер (id > 0)
     *   - зарегистрирован {@code :threshold} назад или раньше (createdAt < threshold)
     *   - не возвращался с момента регистрации (lastActiveAt IS NULL или < threshold)
     *   - email подтверждён (без него не получит письмо, а push без email-уведомлений
     *     слишком слабый сигнал, чтобы тратить FCM-квоту)
     *   - ещё не получал onboarding-reminder (onboardingReminderSent = false)
     *   - не отписан от reminder'ов (reminderOptOut = false)
     */
    @Query("SELECT u FROM User u WHERE u.id > 0 " +
            "AND u.createdAt < :threshold " +
            "AND (u.lastActiveAt IS NULL OR u.lastActiveAt < :threshold) " +
            "AND u.emailVerified = true " +
            "AND u.onboardingReminderSent = false " +
            "AND u.reminderOptOut = false")
    List<User> findOnboardingReminderCandidates(@Param("threshold") LocalDateTime threshold);
}
