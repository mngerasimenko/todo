package ru.mngerasimenko.todolist.service;

/**
 * Сервис управления refresh-токенами с ротацией и reuse detection.
 */
public interface RefreshTokenService {

    /**
     * Создаёт новый refresh-токен для пользователя.
     *
     * @param userId ID пользователя
     * @return raw-токен (UUID строка) для передачи клиенту
     */
    String createRefreshToken(Long userId);

    /**
     * Ротация refresh-токена: старый отзывается, выдаётся новый.
     * При обнаружении повторного использования отозванного токена —
     * отзывается вся семья токенов (reuse detection).
     *
     * @param rawToken raw-токен от клиента
     * @return результат ротации (новый raw-токен + email пользователя)
     * @throws org.springframework.security.authentication.BadCredentialsException если токен невалиден
     */
    RefreshTokenRotationResult rotateRefreshToken(String rawToken);

    /**
     * Отзывает конкретный refresh-токен.
     *
     * @param rawToken raw-токен от клиента
     */
    void revokeByRawToken(String rawToken);

    /**
     * Удаляет все refresh-токены пользователя.
     *
     * @param userId ID пользователя
     */
    void revokeAllForUser(Long userId);

    /**
     * Результат ротации refresh-токена.
     */
    record RefreshTokenRotationResult(String newRawToken, String email) {
    }
}
