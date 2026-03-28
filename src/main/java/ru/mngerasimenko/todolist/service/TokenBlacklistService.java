package ru.mngerasimenko.todolist.service;

import java.time.Instant;

/**
 * Сервис для блокировки access-токенов (in-memory blacklist).
 * Используется при logout для немедленной инвалидации access-токена.
 */
public interface TokenBlacklistService {

    /**
     * Добавляет access-токен в чёрный список до момента его истечения.
     *
     * @param token raw JWT строка
     * @param expiresAt время истечения токена
     */
    void blacklistAccessToken(String token, Instant expiresAt);

    /**
     * Проверяет, находится ли токен в чёрном списке.
     *
     * @param token raw JWT строка
     * @return true если токен заблокирован
     */
    boolean isBlacklisted(String token);

    /**
     * Удаляет истёкшие записи из чёрного списка.
     */
    void evictExpired();
}
