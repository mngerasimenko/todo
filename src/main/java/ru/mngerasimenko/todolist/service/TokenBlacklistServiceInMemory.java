package ru.mngerasimenko.todolist.service;

import lombok.extern.slf4j.Slf4j;
import ru.mngerasimenko.todolist.util.TokenUtils;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory blacklist для access-токенов.
 * Хранит SHA-256 хеш токена → время истечения.
 * Записи автоматически удаляются после истечения срока токена.
 */
@Slf4j
public class TokenBlacklistServiceInMemory implements TokenBlacklistService {

    /**
     * Ключ — SHA-256 хеш токена, значение — время истечения
     */
    private final Map<String, Instant> blacklist = new ConcurrentHashMap<>();

    @Override
    public void blacklistAccessToken(String token, Instant expiresAt) {
        String hash = TokenUtils.sha256(token);
        blacklist.put(hash, expiresAt);
        log.debug("Access-токен добавлен в blacklist, истекает: {}", expiresAt);
    }

    @Override
    public boolean isBlacklisted(String token) {
        String hash = TokenUtils.sha256(token);
        Instant expiresAt = blacklist.get(hash);
        if (expiresAt == null) {
            return false;
        }
        // Если токен уже истёк — убираем из blacklist
        if (expiresAt.isBefore(Instant.now())) {
            blacklist.remove(hash);
            return false;
        }
        return true;
    }

    public void evictExpired() {
        Instant now = Instant.now();
        int before = blacklist.size();
        blacklist.entrySet().removeIf(entry -> entry.getValue().isBefore(now));
        int removed = before - blacklist.size();
        if (removed > 0) {
            log.debug("Очищено {} истёкших записей из blacklist, осталось: {}", removed, blacklist.size());
        }
    }
}
