package ru.mngerasimenko.todolist.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.mngerasimenko.todolist.config.RedisCacheConfig;
import ru.mngerasimenko.todolist.util.TokenUtils;

import java.time.Duration;
import java.time.Instant;

@Service
@Slf4j
@RequiredArgsConstructor
public class TokenBlacklistServiceRedis implements TokenBlacklistService {

    private final StringRedisTemplate redisTemplate;
    private final RedisHealthService redisHealthService;
    private final MeterRegistry meterRegistry;
    private final TokenBlacklistServiceInMemory inMemoryFallback = new TokenBlacklistServiceInMemory();

    @Value("${app.redis.key-prefix:todo}")
    private String keyPrefix;

    @Override
    public void blacklistAccessToken(String token, Instant expiresAt) {
        String hash = TokenUtils.sha256(token);
        String key = keyPrefix + ":blacklist:" + hash;
        long ttlMs = Duration.between(Instant.now(), expiresAt).toMillis();
        if (ttlMs <= 0) { //истек
            log.debug("BlackListToken: Токен {} не добавлен, истек срок", hash);
            return;
        }

        // Circuit breaker: если Redis уже помечен down — сразу пишем в fallback,
        // не делаем бесполезный Lettuce-вызов с timeout.
        if (!redisHealthService.isRedisHealthy()) {
            RedisCacheConfig.incrementFallbackCounter(meterRegistry, "blacklist");
            inMemoryFallback.blacklistAccessToken(token, expiresAt);
            return;
        }

        try {
            redisTemplate.opsForValue().set(key, "1", Duration.ofMillis(ttlMs));
        } catch (DataAccessException ex) {
            log.warn("Redis недоступен, blacklist → in-memory fallback: {}", ex.getMessage());
            redisHealthService.markUnhealthy();
            RedisCacheConfig.incrementFallbackCounter(meterRegistry, "blacklist");
            inMemoryFallback.blacklistAccessToken(token, expiresAt);
        }
    }

    @Override
    public boolean isBlacklisted(String token) {
        String hash = TokenUtils.sha256(token);
        String key = keyPrefix + ":blacklist:" + hash;

        // Circuit breaker: если Redis помечен down — сразу читаем из fallback.
        if (!redisHealthService.isRedisHealthy()) {
            RedisCacheConfig.incrementFallbackCounter(meterRegistry, "blacklist");
            return inMemoryFallback.isBlacklisted(token);
        }

        try {
            Boolean exists = redisTemplate.hasKey(key);
            if (Boolean.TRUE.equals(exists)) return true;
            // на всякий случай проверяем fallback (если запись ушла туда при сбое)
            return inMemoryFallback.isBlacklisted(token);
        } catch (DataAccessException ex) {
            log.warn("Redis недоступен, проверка blacklist → in-memory fallback: {}", ex.getMessage());
            redisHealthService.markUnhealthy();
            RedisCacheConfig.incrementFallbackCounter(meterRegistry, "blacklist");
            return inMemoryFallback.isBlacklisted(token);
        }
    }

    @Scheduled(fixedRate = 600000)
    public void cleanupFallback() {
        inMemoryFallback.evictExpired();
    }
}
