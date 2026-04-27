package ru.mngerasimenko.todolist.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Тесты для CacheErrorHandler из RedisCacheConfig.
 * Проверяют graceful degradation: ни одна ошибка не пробрасывается, counter
 * "cache.errors" инкрементируется с тегами cache + operation.
 */
class CacheErrorHandlerTest {

    private MeterRegistry meterRegistry;
    private CacheErrorHandler handler;
    private Cache cache;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        RedisCacheConfig config = new RedisCacheConfig(meterRegistry);
        handler = config.cacheErrorHandler(meterRegistry);
        cache = mock(Cache.class);
        when(cache.getName()).thenReturn("test-cache");
    }

    @Test
    void handleCacheGetError_DoesNotThrow_AndIncrementsTaggedCounter() {
        RedisConnectionFailureException ex = new RedisConnectionFailureException("connection refused");

        handler.handleCacheGetError(ex, cache, "key-1");

        assertThat(counterCount("test-cache", "get")).isEqualTo(1.0);
    }

    @Test
    void handleCachePutError_DoesNotThrow_AndIncrementsTaggedCounter() {
        RedisConnectionFailureException ex = new RedisConnectionFailureException("connection refused");

        handler.handleCachePutError(ex, cache, "key-2", "value-2");

        assertThat(counterCount("test-cache", "put")).isEqualTo(1.0);
    }

    @Test
    void handleCacheEvictError_DoesNotThrow_AndIncrementsTaggedCounter() {
        QueryTimeoutException ex = new QueryTimeoutException("redis timeout");

        handler.handleCacheEvictError(ex, cache, "key-3");

        assertThat(counterCount("test-cache", "evict")).isEqualTo(1.0);
    }

    @Test
    void handleCacheClearError_DoesNotThrow_AndIncrementsTaggedCounter() {
        RedisConnectionFailureException ex = new RedisConnectionFailureException("connection refused");

        handler.handleCacheClearError(ex, cache);

        assertThat(counterCount("test-cache", "clear")).isEqualTo(1.0);
    }

    @Test
    void multipleErrors_RegisterSeparateCountersPerOperation() {
        RedisConnectionFailureException ex = new RedisConnectionFailureException("down");

        handler.handleCacheGetError(ex, cache, "k1");
        handler.handleCachePutError(ex, cache, "k2", "v");
        handler.handleCacheEvictError(ex, cache, "k3");
        handler.handleCacheClearError(ex, cache);

        assertThat(counterCount("test-cache", "get")).isEqualTo(1.0);
        assertThat(counterCount("test-cache", "put")).isEqualTo(1.0);
        assertThat(counterCount("test-cache", "evict")).isEqualTo(1.0);
        assertThat(counterCount("test-cache", "clear")).isEqualTo(1.0);

        double total = meterRegistry.find("cache.errors").counters().stream()
                .mapToDouble(Counter::count)
                .sum();
        assertThat(total).isEqualTo(4.0);
    }

    @Test
    void repeatedSameOperation_AccumulatesInSameCounter() {
        RedisConnectionFailureException ex = new RedisConnectionFailureException("down");

        handler.handleCacheGetError(ex, cache, "k1");
        handler.handleCacheGetError(ex, cache, "k2");
        handler.handleCacheGetError(ex, cache, "k3");

        assertThat(counterCount("test-cache", "get")).isEqualTo(3.0);
    }

    @Test
    void differentCaches_RegisterSeparateCounters() {
        Cache otherCache = mock(Cache.class);
        when(otherCache.getName()).thenReturn("other-cache");
        RedisConnectionFailureException ex = new RedisConnectionFailureException("down");

        handler.handleCacheGetError(ex, cache, "k1");
        handler.handleCacheGetError(ex, otherCache, "k2");
        handler.handleCacheGetError(ex, otherCache, "k3");

        assertThat(counterCount("test-cache", "get")).isEqualTo(1.0);
        assertThat(counterCount("other-cache", "get")).isEqualTo(2.0);
    }

    private double counterCount(String cacheName, String operation) {
        Counter counter = meterRegistry.find("cache.errors")
                .tag("cache", cacheName)
                .tag("operation", operation)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }
}
