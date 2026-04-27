package ru.mngerasimenko.todolist.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.mngerasimenko.todolist.service.RedisHealthService;

/**
 * Фоновая проверка Redis каждые 30 секунд.
 * Результат кешируется в RedisHealthService — /api/status отдаёт мгновенно.
 * Отключается в тестах через app.redis-health-check.enabled=false.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.redis-health-check.enabled", havingValue = "true", matchIfMissing = true)
public class RedisHealthScheduler {

    private final RedisHealthService redisHealthService;

    @Scheduled(fixedRate = 30_000, initialDelay = 5_000)
    public void checkRedisHealth() {
        redisHealthService.checkRedisHealth();
    }
}
