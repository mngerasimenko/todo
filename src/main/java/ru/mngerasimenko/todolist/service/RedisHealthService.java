package ru.mngerasimenko.todolist.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Service;

/**
 * Сервис проверки доступности Redis.
 *
 * Метод {@link #checkRedisHealth()} вызывается планировщиком каждые 30 сек,
 * результат кешируется в volatile-поле. {@link #isRedisHealthy()} читает кеш —
 * подходит для горячего пути /api/status (не блокирует на сетевом запросе).
 *
 * По образу EmailServiceImpl.isSmtpHealthy() / SmtpHealthScheduler.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RedisHealthService {

    private final RedisConnectionFactory connectionFactory;

    /**
     * Кеш статуса. Перед первой проверкой = false (не считаем healthy без подтверждения).
     */
    private volatile boolean redisHealthyCache = false;

    public boolean isRedisHealthy() {
        return redisHealthyCache;
    }

    /**
     * Выполняет PING и обновляет кеш.
     * Любая ошибка (RedisConnectionFailureException, timeout, etc.) → healthy=false.
     */
    public void checkRedisHealth() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            String pong = connection.ping();
            redisHealthyCache = "PONG".equalsIgnoreCase(pong);
            if (!redisHealthyCache) {
                log.warn("Redis PING вернул неожиданный ответ: {}", pong);
            }
        } catch (Exception ex) {
            log.warn("Redis health-check завершился с ошибкой: {}", ex.toString());
            redisHealthyCache = false;
        }
    }
}
