package ru.mngerasimenko.todolist.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.mngerasimenko.todolist.util.TokenUtils;

import java.time.Duration;
import java.time.Instant;

@Service
@Slf4j
@RequiredArgsConstructor
public class TokenBlacklistServiceRedis implements TokenBlacklistService {

    private final StringRedisTemplate redisTemplate;
    private final TokenBlacklistServiceInMemory inMemoryFallBack = new TokenBlacklistServiceInMemory();

    @Value("${app.redis.key-prefix:todo}")
    private String keyPrefix;

    @Override
    public void blacklistAccessToken(String token, Instant expiresAt) {
        String hash = TokenUtils.sha256(token);
        String key = keyPrefix + ":blacklist:" + hash;
        long ttlMs = Duration.between(Instant.now(), expiresAt).toMillis();
        if (ttlMs <= 0) return; //истек

        try {
            redisTemplate.opsForValue().set(key, "1", Duration.ofMillis(ttlMs));
        } catch (DataAccessException ex) {
            log.warn("Redis недоступен, blacklist → in-memory fallback: {}", ex.getMessage());
            inMemoryFallBack.blacklistAccessToken(token, expiresAt);
        }
    }

    @Override
    public boolean isBlacklisted(String token) {
        String hash = TokenUtils.sha256(token);
        String key = keyPrefix + ":blacklist:" + hash;
        try {
            Boolean exists = redisTemplate.hasKey(key);
            if (Boolean.TRUE.equals(exists)) return true;
            // на всякий случай проверяем fallback (если запись ушла туда при сбое)
            return inMemoryFallBack.isBlacklisted(token);
        } catch (DataAccessException ex) {
            log.warn("Redis недоступен, проверка blacklist → in-memory fallback: {}", ex.getMessage());
            return inMemoryFallBack.isBlacklisted(token);
        }
    }

    @Scheduled(fixedRate = 600000)
    private void cleanupFallback() {
        inMemoryFallBack.evictExpired();
    }
}
