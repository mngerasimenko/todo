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

    User getUserByEmail(String email);

    User getUserById(Long id);

    User getUserByName(String userName);

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
     */
    @Query("SELECT u FROM User u WHERE u.id > 0 " +
            "AND (u.lastActiveAt IS NULL OR u.lastActiveAt < :inactiveSince) " +
            "AND u.reminderCount < :maxReminders")
    List<User> findInactiveUsersForReminder(
            @Param("inactiveSince") LocalDateTime inactiveSince,
            @Param("maxReminders") int maxReminders);
}
