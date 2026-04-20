package ru.mngerasimenko.todolist.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Периодическая очистка неактивных rate limit bucket'ов.
 * Активен только в memory-режиме (в redis-режиме eviction делает сам Redis через TTL ключей).
 */
@Component
@ConditionalOnBean(BucketProviderInMemory.class)
@RequiredArgsConstructor
@Slf4j
public class RateLimitCleanupScheduler {

    private static final long MAX_IDLE_MILLIS = 2 * 60 * 60 * 1000L; // 2 часа

    private final BucketProviderInMemory bucketProvider;

    @Scheduled(fixedDelayString = "${rate-limit.cleanup-interval-ms:600000}")
    public void cleanup() {
        int before = bucketProvider.getActiveBucketCount();
        bucketProvider.evictExpiredBuckets(MAX_IDLE_MILLIS);
        int after = bucketProvider.getActiveBucketCount();

        if (before != after) {
            log.info("Rate limit cleanup: удалено {} неактивных bucket'ов, осталось {}",
                    before - after, after);
        }
    }
}
