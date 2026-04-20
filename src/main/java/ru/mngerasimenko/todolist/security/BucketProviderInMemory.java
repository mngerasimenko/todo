package ru.mngerasimenko.todolist.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.local.LocalBucketBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Локальная реализация BucketProvider на основе ConcurrentHashMap.
 * Состояние bucket'ов живёт только в памяти JVM и сбрасывается при рестарте контейнера.
 * Не разделяется между инстансами приложения — при горизонтальном масштабировании
 * каждый инстанс держит свой счётчик.
 * Активна при rate-limit.storage=memory (значение по умолчанию).
 */
@Component
@ConditionalOnProperty(name = "rate-limit.storage", havingValue = "memory", matchIfMissing = true)
public class BucketProviderInMemory implements BucketProvider {

    /**
     * Хранилище bucket'ов: ключ = "тип:IP"
     */
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * Время последнего использования bucket'а для очистки
     */
    private final Map<String, Long> lastAccessTime = new ConcurrentHashMap<>();

    @Override
    public Bucket resolveBucket(String key, BucketConfiguration config) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> buildBucket(config));
        lastAccessTime.put(key, System.currentTimeMillis());
        return bucket;
    }

    private Bucket buildBucket(BucketConfiguration config) {
        LocalBucketBuilder builder = Bucket.builder();
        for (Bandwidth bandwidth : config.getBandwidths()) {
            builder.addLimit(bandwidth);
        }

        return builder.build();
    }

    /**
     * Удаляет bucket'ы, которые не использовались дольше указанного времени.
     * Вызывается из планировщика для предотвращения утечки памяти.
     */
    public void evictExpiredBuckets(long maxIdleMillis) {
        long now = System.currentTimeMillis();
        lastAccessTime.entrySet().removeIf(entry -> {
            if (now - entry.getValue() >= maxIdleMillis) {
                buckets.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    /**
     * Количество активных bucket'ов (для мониторинга и тестов).
     */
    public int getActiveBucketCount() {
        return buckets.size();
    }
}
