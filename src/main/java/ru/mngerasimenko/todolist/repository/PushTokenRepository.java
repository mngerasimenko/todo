package ru.mngerasimenko.todolist.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.mngerasimenko.todolist.model.PushToken;

import java.util.List;
import java.util.Optional;

/**
 * Репозиторий для хранения FCM push-токенов устройств.
 */
public interface PushTokenRepository extends JpaRepository<PushToken, Long> {

    /**
     * Найти токен по deviceId (одно устройство = один токен).
     */
    Optional<PushToken> findByDeviceId(String deviceId);

    /**
     * Все токены пользователя (все устройства).
     */
    List<PushToken> findByUserId(Long userId);

    /**
     * Все push-токены участников списка (кроме указанного пользователя).
     * Используется для отправки push всем участникам кроме автора действия.
     * Возвращает полный entity (включая {@code locale}) для per-token локализации.
     */
    @Query("SELECT pt FROM PushToken pt " +
            "JOIN TaskListUser tlu ON tlu.user.id = pt.user.id " +
            "WHERE tlu.id.listId = :listId AND pt.user.id != :excludeUserId")
    List<PushToken> findByListIdExcludingUser(Long listId, Long excludeUserId);

    /**
     * Найти токен по FCM-токену (для удаления невалидных).
     */
    Optional<PushToken> findByFcmToken(String fcmToken);

    /**
     * Удалить все токены пользователя (при logout/удалении аккаунта).
     */
    void deleteByUserId(Long userId);

    /**
     * Удалить токен по deviceId (при logout с конкретного устройства).
     */
    void deleteByDeviceId(String deviceId);
}
