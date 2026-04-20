package ru.mngerasimenko.todolist.security;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Распределённая реализация BucketProvider через Redis.
 * Состояние bucket'ов переживает рестарт контейнера и разделяется между инстансами приложения.
 * Активна при rate-limit.storage=redis.
 */
@Component
@ConditionalOnProperty(name = "rate-limit.storage", havingValue = "redis")
@RequiredArgsConstructor
public class BucketProviderRedis implements BucketProvider {

    private final LettuceBasedProxyManager<String> proxyManager;

    @Override
    public Bucket resolveBucket(String key, BucketConfiguration config) {
        return proxyManager.getProxy(key, () -> config);
    }
}
