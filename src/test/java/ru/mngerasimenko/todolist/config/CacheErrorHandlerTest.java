package ru.mngerasimenko.todolist.config;

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
 * Проверяют graceful degradation: ни одна ошибка не пробрасывается, counter инкрементируется.
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
    void handleCacheGetError_DoesNotThrow_AndIncrementsCounter() {
        RedisConnectionFailureException ex = new RedisConnectionFailureException("connection refused");

        handler.handleCacheGetError(ex, cache, "key-1");

        assertThat(meterRegistry.counter("cache.errors").count()).isEqualTo(1.0);
    }

    @Test
    void handleCachePutError_DoesNotThrow_AndIncrementsCounter() {
        RedisConnectionFailureException ex = new RedisConnectionFailureException("connection refused");

        handler.handleCachePutError(ex, cache, "key-2", "value-2");

        assertThat(meterRegistry.counter("cache.errors").count()).isEqualTo(1.0);
    }

    @Test
    void handleCacheEvictError_DoesNotThrow_AndIncrementsCounter() {
        QueryTimeoutException ex = new QueryTimeoutException("redis timeout");

        handler.handleCacheEvictError(ex, cache, "key-3");

        assertThat(meterRegistry.counter("cache.errors").count()).isEqualTo(1.0);
    }

    @Test
    void handleCacheClearError_DoesNotThrow_AndIncrementsCounter() {
        RedisConnectionFailureException ex = new RedisConnectionFailureException("connection refused");

        handler.handleCacheClearError(ex, cache);

        assertThat(meterRegistry.counter("cache.errors").count()).isEqualTo(1.0);
    }

    @Test
    void multipleErrors_IncrementCounterCumulatively() {
        RedisConnectionFailureException ex = new RedisConnectionFailureException("down");

        handler.handleCacheGetError(ex, cache, "k1");
        handler.handleCachePutError(ex, cache, "k2", "v");
        handler.handleCacheEvictError(ex, cache, "k3");
        handler.handleCacheClearError(ex, cache);

        assertThat(meterRegistry.counter("cache.errors").count()).isEqualTo(4.0);
    }
}
