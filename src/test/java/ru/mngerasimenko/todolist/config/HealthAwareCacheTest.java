package ru.mngerasimenko.todolist.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import ru.mngerasimenko.todolist.service.RedisHealthService;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты для HealthAwareCache и HealthAwareCacheManager — circuit breaker
 * над Spring Cache. При !isRedisHealthy() кэш-операции no-op без обращения
 * к Redis (что экономит timeout-ожидания).
 */
class HealthAwareCacheTest {

    private Cache delegate;
    private RedisHealthService health;
    private MeterRegistry meterRegistry;
    private RedisCacheConfig.HealthAwareCache cache;

    @BeforeEach
    void setUp() {
        delegate = mock(Cache.class);
        when(delegate.getName()).thenReturn("test-cache");
        health = mock(RedisHealthService.class);
        meterRegistry = new SimpleMeterRegistry();
        cache = new RedisCacheConfig.HealthAwareCache(delegate, health, meterRegistry);
    }

    // --- Healthy → делегирование ---

    @Test
    void get_HealthUp_DelegatesToUnderlying() {
        when(health.isRedisHealthy()).thenReturn(true);
        Cache.ValueWrapper wrapped = mock(Cache.ValueWrapper.class);
        when(delegate.get("k1")).thenReturn(wrapped);

        Cache.ValueWrapper result = cache.get("k1");

        assertThat(result).isSameAs(wrapped);
        verify(delegate).get("k1");
    }

    @Test
    void put_HealthUp_DelegatesToUnderlying() {
        when(health.isRedisHealthy()).thenReturn(true);

        cache.put("k1", "v1");

        verify(delegate).put("k1", "v1");
    }

    @Test
    void evict_HealthUp_DelegatesToUnderlying() {
        when(health.isRedisHealthy()).thenReturn(true);

        cache.evict("k1");

        verify(delegate).evict("k1");
    }

    // --- Circuit breaker: health=down → no-op ---

    @Test
    void get_HealthDown_ReturnsNull_AndIncrementsCounter() {
        when(health.isRedisHealthy()).thenReturn(false);

        Cache.ValueWrapper result = cache.get("k1");

        assertThat(result).isNull();
        verifyNoInteractions(delegate.getClass() == Cache.class ? null : delegate);
        verify(delegate, never()).get(any());
        assertThat(meterRegistry.counter("redis.fallback", "component", "cache").count()).isEqualTo(1.0);
    }

    @Test
    void getTyped_HealthDown_ReturnsNull() {
        when(health.isRedisHealthy()).thenReturn(false);

        String result = cache.get("k1", String.class);

        assertThat(result).isNull();
        verify(delegate, never()).get(any(), eq(String.class));
    }

    @Test
    void getWithLoader_HealthDown_CallsLoaderInsteadOfDelegate() throws Exception {
        when(health.isRedisHealthy()).thenReturn(false);
        @SuppressWarnings("unchecked")
        Callable<String> loader = mock(Callable.class);
        when(loader.call()).thenReturn("loaded-value");

        String result = cache.get("k1", loader);

        assertThat(result).isEqualTo("loaded-value");
        verify(loader).call();
        verify(delegate, never()).get(any(), eq(loader));
    }

    @Test
    void put_HealthDown_NoOp() {
        when(health.isRedisHealthy()).thenReturn(false);

        cache.put("k1", "v1");

        verify(delegate, never()).put(any(), any());
    }

    @Test
    void evict_HealthDown_NoOp() {
        when(health.isRedisHealthy()).thenReturn(false);

        cache.evict("k1");

        verify(delegate, never()).evict(any());
    }

    @Test
    void evictIfPresent_HealthDown_ReturnsFalse() {
        when(health.isRedisHealthy()).thenReturn(false);

        boolean evicted = cache.evictIfPresent("k1");

        assertThat(evicted).isFalse();
        verify(delegate, never()).evictIfPresent(any());
    }

    @Test
    void clear_HealthDown_NoOp() {
        when(health.isRedisHealthy()).thenReturn(false);

        cache.clear();

        verify(delegate, never()).clear();
    }

    @Test
    void invalidate_HealthDown_ReturnsFalse() {
        when(health.isRedisHealthy()).thenReturn(false);

        boolean invalidated = cache.invalidate();

        assertThat(invalidated).isFalse();
        verify(delegate, never()).invalidate();
    }

    // --- Имя/native всегда делегируются (даже при health=down — это безобидные read-only ops) ---

    @Test
    void getName_AlwaysDelegates() {
        assertThat(cache.getName()).isEqualTo("test-cache");
    }

    // --- Counter инкрементируется на каждый bypass ---

    @Test
    void multipleGets_HealthDown_CounterAccumulates() {
        when(health.isRedisHealthy()).thenReturn(false);

        cache.get("k1");
        cache.get("k2");
        cache.get("k3");

        assertThat(meterRegistry.counter("redis.fallback", "component", "cache").count()).isEqualTo(3.0);
    }

    // --- HealthAwareCacheManager делегирует getCacheNames и оборачивает getCache ---

    @Test
    void manager_GetCacheNames_DelegatesToUnderlying() {
        org.springframework.cache.CacheManager underlying = mock(org.springframework.cache.CacheManager.class);
        when(underlying.getCacheNames()).thenReturn(List.of("a", "b"));
        RedisCacheConfig.HealthAwareCacheManager mgr =
                new RedisCacheConfig.HealthAwareCacheManager(underlying, health, meterRegistry);

        Collection<String> names = mgr.getCacheNames();

        assertThat(names).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void manager_GetCache_WrapsUnderlyingCache() {
        org.springframework.cache.CacheManager underlying = mock(org.springframework.cache.CacheManager.class);
        Cache underlyingCache = mock(Cache.class);
        when(underlying.getCache("c1")).thenReturn(underlyingCache);
        RedisCacheConfig.HealthAwareCacheManager mgr =
                new RedisCacheConfig.HealthAwareCacheManager(underlying, health, meterRegistry);

        Cache wrapped = mgr.getCache("c1");

        assertThat(wrapped).isInstanceOf(RedisCacheConfig.HealthAwareCache.class);
    }

    @Test
    void manager_GetCache_NullFromUnderlying_ReturnsNull() {
        org.springframework.cache.CacheManager underlying = mock(org.springframework.cache.CacheManager.class);
        when(underlying.getCache("missing")).thenReturn(null);
        RedisCacheConfig.HealthAwareCacheManager mgr =
                new RedisCacheConfig.HealthAwareCacheManager(underlying, health, meterRegistry);

        assertThat(mgr.getCache("missing")).isNull();
    }
}
