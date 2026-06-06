package ru.mngerasimenko.todolist.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * Атомарный upsert по {@code device_id}. Заменяет паттерн {@code findByDeviceId + save},
     * который при двух одновременных регистрациях того же устройства давал либо
     * {@code DataIntegrityViolationException} (INSERT-конфликт), либо «last writer wins»
     * с потерей предыдущего значения.
     *
     * Native query (PostgreSQL): {@code ON CONFLICT (device_id) DO UPDATE} — одно атомарное
     * statement, без двойного round-trip к БД, без шанса промахнуться в гонке.
     * {@code user_id} обновляется тоже: устройство могло сменить пользователя при logout
     * → login другим аккаунтом.
     */
    @Modifying
    @Query(value = """
            INSERT INTO push_token (user_id, fcm_token, device_id, locale, created_at, updated_at)
            VALUES (:userId, :fcmToken, :deviceId, :locale, NOW(), NOW())
            ON CONFLICT (device_id) DO UPDATE SET
                user_id = EXCLUDED.user_id,
                fcm_token = EXCLUDED.fcm_token,
                locale = EXCLUDED.locale,
                updated_at = NOW()
            """, nativeQuery = true)
    void upsertByDeviceId(@Param("userId") Long userId,
                          @Param("fcmToken") String fcmToken,
                          @Param("deviceId") String deviceId,
                          @Param("locale") String locale);
}
