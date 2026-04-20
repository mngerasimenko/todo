package ru.mngerasimenko.todolist.security;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;

/**
 * Абстракция хранилища bucket'ов rate limiting.
 * Реализации: in-memory (ConcurrentHashMap) и Redis (LettuceBasedProxyManager).
 */
public interface BucketProvider {

    /**
     * Возвращает bucket для заданного ключа. Если bucket не существует — создаёт его с конфигурацией config.
     * В распределённой реализации конфигурация применяется лениво на стороне хранилища.
     *
     * @param key    уникальный ключ (например, "login:192.168.1.10")
     * @param config конфигурация bucket'а (capacity, refill)
     * @return bucket для потребления токенов
     */
    Bucket resolveBucket(String key, BucketConfiguration config);
}
