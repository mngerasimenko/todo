package ru.mngerasimenko.todolist.service;

import jakarta.annotation.PostConstruct;
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
     * Синхронный warmup при старте контекста: scheduler опрашивает Redis раз в 30 сек,
     * и в первое окно после старта {@link #isRedisHealthy()} вернёт false →
     * {@code HealthAwareCache.put()} no-op → кэш молча не работает первые 30 сек.
     * Один PING при инициализации закрывает эту дыру: если Redis уже доступен,
     * healthy=true сразу; если нет — будет восстановлено следующим тиком scheduler'а.
     */
    @PostConstruct
    void warmupHealthCheck() {
        checkRedisHealth();
    }

    /**
     * Помечает Redis как недоступный сразу, не дожидаясь следующего тика scheduler'а
     * (раз в 30 сек). Вызывается компонентами hot-path (RateLimitFilter,
     * TokenBlacklistServiceRedis, CacheErrorHandler) при поимке Redis-исключения.
     *
     * Эффект: следующий запрос увидит {@code isRedisHealthy() == false} и сразу
     * пойдёт через fallback, не делая бесполезный Lettuce-запрос с timeout.
     * Окно «медленных» запросов сокращается с ~30 сек до 1.
     *
     * Восстановление автоматическое — следующий успешный PING из scheduler'а
     * вернёт {@code redisHealthyCache = true}.
     */
    public void markUnhealthy() {
        redisHealthyCache = false;
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
