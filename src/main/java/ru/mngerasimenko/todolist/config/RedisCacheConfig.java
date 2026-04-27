package ru.mngerasimenko.todolist.config;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import ru.mngerasimenko.todolist.service.RedisHealthService;

import java.util.Collection;
import java.util.concurrent.Callable;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import ru.mngerasimenko.todolist.dto.AuthUserDto;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.dto.list.ListResponse;

import java.time.Duration;
import java.util.List;

/**
 * Конфигурация Spring Cache через Redis (spring-boot-starter-cache + spring-data-redis).
 *
 * Кэшируются hot-paths с коротким TTL:
 * - {@code users-me} (60 сек) — ответ GET /api/users/me, ключ = email (lowercase).
 * - {@code task-lists} (60 сек) — ответ GET /api/lists, ключ = userId.
 * - {@code user-auth} (60 сек) — {@link AuthUserDto} для Spring Security auth (JWT-filter + login),
 *   ключ = email (lowercase). Содержит password (BCrypt-hash), инвалидируется во всех
 *   мутациях User: resetPassword, updateUser, changeEmail, verifyEmail, updateColors, delete.
 *   Тип отдельный от {@link UserDto}, потому что у {@code UserDto.password} стоит
 *   {@code @JsonIgnore} и Jackson-сериализация теряет password при записи в Redis.
 *
 * Для каждого кэша — отдельный типизированный {@link Jackson2JsonRedisSerializer}
 * (не общий polymorphic). Это проще и безопаснее: не нужен
 * {@code activateDefaultTyping} (ломается на root-level коллекциях) и
 * {@code BasicPolymorphicTypeValidator} (не нужен — типы фиксированы).
 *
 * За основу берём Spring Boot auto-configured {@link ObjectMapper} — в нём уже
 * зарегистрированы {@code JavaTimeModule}, {@code ParameterNamesModule}, все
 * пользовательские {@code Jackson2ObjectMapperBuilderCustomizer} и настройки
 * {@code spring.jackson.*}. Любые будущие Jackson-модули автоматически
 * попадают в Redis-сериализацию.
 *
 * Runtime-отключение — через {@link ru.mngerasimenko.todolist.featureflags.FeatureFlag#RESPONSE_CACHE}
 * (SpEL-condition в @Cacheable-методах).
 */
@Configuration
@EnableCaching
@Slf4j
public class RedisCacheConfig implements CachingConfigurer {

    public static final String USERS_ME = "users-me";
    public static final String TASK_LISTS = "task-lists";
    public static final String USER_AUTH = "user-auth";

    /**
     * SpEL-условие для @Cacheable — проверяет feature flag RESPONSE_CACHE.
     * Вынесено в константу, чтобы повторно использовать в нескольких сервисах.
     */
    public static final String CACHE_CONDITION =
            "@featureFlagStore.isEnabled(T(ru.mngerasimenko.todolist.featureflags.FeatureFlag).RESPONSE_CACHE)";

    private final MeterRegistry meterRegistry;
    private final RedisHealthService redisHealthService;

    public RedisCacheConfig(MeterRegistry meterRegistry, RedisHealthService redisHealthService) {
        this.meterRegistry = meterRegistry;
        this.redisHealthService = redisHealthService;
    }

    /**
     * RedisCacheManager регистрируется как самостоятельный bean, чтобы Spring сам
     * вызвал {@link RedisCacheManager#afterPropertiesSet()} и подгрузил initialCaches
     * (USERS_ME / TASK_LISTS / USER_AUTH со своими Jackson-сериализаторами).
     *
     * Раньше builder().build() вызывался внутри метода cacheManager(), результат
     * не был bean'ом, afterPropertiesSet() не вызывался — initialCaches оставались
     * незагруженными, и при getCache(name) Spring создавал кэши через
     * cacheDefaults без serializeValuesWith → дефолтный JdkSerializationRedisSerializer
     * пытался сериализовать DTO без Serializable → SerializationException на каждом
     * cache PUT.
     */
    @Bean
    public RedisCacheManager redisCacheManager(RedisConnectionFactory connectionFactory,
                                               ObjectMapper appObjectMapper) {
        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .disableCachingNullValues()
                .serializeKeysWith(SerializationPair.fromSerializer(new StringRedisSerializer()));

        // Отдельный serializer для каждого кэша — по фиксированному типу.
        RedisSerializer<UserDto> userDtoSerializer =
                new Jackson2JsonRedisSerializer<>(appObjectMapper, UserDto.class);
        RedisSerializer<AuthUserDto> authUserDtoSerializer =
                new Jackson2JsonRedisSerializer<>(appObjectMapper, AuthUserDto.class);

        JavaType listResponseType = appObjectMapper.getTypeFactory()
                .constructCollectionType(List.class, ListResponse.class);
        @SuppressWarnings({"unchecked", "rawtypes"})
        RedisSerializer<List<ListResponse>> taskListsSerializer =
                (RedisSerializer) new Jackson2JsonRedisSerializer<>(appObjectMapper, listResponseType);

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(base)
                .withCacheConfiguration(USERS_ME, base
                        .entryTtl(Duration.ofSeconds(60))
                        .serializeValuesWith(SerializationPair.fromSerializer(userDtoSerializer)))
                .withCacheConfiguration(TASK_LISTS, base
                        .entryTtl(Duration.ofSeconds(60))
                        .serializeValuesWith(SerializationPair.fromSerializer(taskListsSerializer)))
                .withCacheConfiguration(USER_AUTH, base
                        .entryTtl(Duration.ofSeconds(60))
                        .serializeValuesWith(SerializationPair.fromSerializer(authUserDtoSerializer)))
                .build();
    }

    /**
     * @Primary — Spring видит два бина типа CacheManager (RedisCacheManager и
     * HealthAwareCacheManager-обёртка), и для разрешения @Cacheable должен выбрать
     * именно обёртку с circuit breaker'ом, а не голый RedisCacheManager.
     */
    @Bean
    @Primary
    public CacheManager cacheManager(RedisCacheManager redisCacheManager,
                                     RedisHealthService redisHealthService) {
        // Оборачиваем в circuit breaker: при !redisHealthService.isRedisHealthy()
        // все cache-операции no-op без обращения к Redis. Это убирает 4 timeout-ожидания
        // (по 300мс каждое) на каждом запросе во время сбоя Redis — суммарно ~2 сек/запрос.
        return new HealthAwareCacheManager(redisCacheManager, redisHealthService, meterRegistry);
    }

    /**
     * CacheErrorHandler — graceful degradation при недоступности Redis.
     *
     * Все 4 callback'а логируют WARN и инкрементируют counter "cache.errors"
     * с тегами {@code cache} (имя кэша) и {@code operation} (get/put/evict/clear).
     * Тегирование позволяет в Grafana/Prometheus разделять ошибки по типу кэша
     * (например, ошибка в "user-auth" критичнее, чем в "task-lists").
     *
     * Исключения НЕ пробрасываются:
     *   - get-error → Spring трактует как cache miss → метод выполняется (медленнее, но работает).
     *   - put-error → результат метода возвращается клиенту, в кэш не попадает.
     *   - evict-error → stale-данные живут максимум до TTL=60 сек.
     *   - clear-error → аналогично evict.
     *
     * Подключается через {@link CachingConfigurer#errorHandler()} — глобально на все CacheManager.
     */
    @Bean
    public CacheErrorHandler cacheErrorHandler(MeterRegistry meterRegistry) {
        return new CacheErrorHandler() {
            // SLF4J trick: если последний параметр — Throwable и плейсхолдеров {} столько же,
            // сколько остальных параметров, Logback не подставляет его в {}, а печатает
            // полный stack trace с caused-by. Так не теряется реальная причина
            // (например, JsonMappingException из Jackson при сериализации DTO).
            @Override
            public void handleCacheGetError(RuntimeException ex, Cache cache, Object key) {
                log.warn("Redis cache GET error in '{}' for key={}", cache.getName(), key, ex);
                incrementErrorCounter(meterRegistry, cache.getName(), "get");
                markRedisUnhealthyIfPossible();
            }

            @Override
            public void handleCachePutError(RuntimeException ex, Cache cache, Object key, Object value) {
                log.warn("Redis cache PUT error in '{}' for key={}", cache.getName(), key, ex);
                incrementErrorCounter(meterRegistry, cache.getName(), "put");
                markRedisUnhealthyIfPossible();
            }

            @Override
            public void handleCacheEvictError(RuntimeException ex, Cache cache, Object key) {
                log.warn("Redis cache EVICT error in '{}' for key={}", cache.getName(), key, ex);
                incrementErrorCounter(meterRegistry, cache.getName(), "evict");
                markRedisUnhealthyIfPossible();
            }

            @Override
            public void handleCacheClearError(RuntimeException ex, Cache cache) {
                log.warn("Redis cache CLEAR error in '{}'", cache.getName(), ex);
                incrementErrorCounter(meterRegistry, cache.getName(), "clear");
                markRedisUnhealthyIfPossible();
            }

            // Помечает Redis как недоступный, если RedisHealthService инжектирован.
            // null-проверка — защита для unit-тестов, где этот сервис может не передаваться.
            private void markRedisUnhealthyIfPossible() {
                if (redisHealthService != null) {
                    redisHealthService.markUnhealthy();
                }
            }
        };
    }

    /**
     * Регистрирует (или находит уже зарегистрированный) counter "cache.errors"
     * с тегами cache+operation и инкрементирует его. Micrometer кеширует counter
     * по name+tags, повторный вызов не создаёт нового объекта.
     */
    private static void incrementErrorCounter(MeterRegistry registry, String cacheName, String operation) {
        Counter.builder("cache.errors")
                .description("Ошибки операций Spring Cache (Redis недоступен и т.п.)")
                .tag("cache", cacheName)
                .tag("operation", operation)
                .register(registry)
                .increment();
    }

    /**
     * Диагностический ApplicationRunner — печатает фактический тип CacheErrorHandler,
     * который Spring AOP в итоге подключил к CacheInterceptor. Если в логе мы видим
     * имя нашего анонимного класса (RedisCacheConfig$1) — значит CachingConfigurer
     * сработал. Если SimpleCacheErrorHandler — значит наш override проигнорирован.
     *
     * Временно для расследования бага graceful degradation на staging (см. fix-ветку).
     */
    @Bean
    public ApplicationRunner cacheErrorHandlerDiagnostic(CacheInterceptor cacheInterceptor) {
        return args -> {
            CacheErrorHandler effective = cacheInterceptor.getErrorHandler();
            log.info("DIAGNOSTIC: effective CacheErrorHandler in CacheInterceptor = {}",
                    effective == null ? "null" : effective.getClass().getName());
        };
    }

    /**
     * Override CachingConfigurer.errorHandler() — Spring подключит handler глобально
     * ко всем CacheManager. Создаём новый instance через bean-метод (Micrometer counter
     * идемпотентен при повторной регистрации по имени, поэтому безопасно).
     * Не self-inject bean, чтобы избежать circular initialization.
     *
     * INFO-лог при вызове — диагностика: если строка не появилась в логе при старте,
     * значит Spring CachingConfigurer не подхватил наш override (см. fix/redis-graceful-degradation).
     */
    @Override
    public CacheErrorHandler errorHandler() {
        log.info("CacheErrorHandler registered globally via CachingConfigurer (cache.errors counter active)");
        return cacheErrorHandler(meterRegistry);
    }

    /**
     * Общий counter "redis.fallback" для всех компонентов resilience-стека.
     * Тег {@code component} = {@code rate_limit | blacklist | cache} — позволяет в
     * Grafana строить агрегированный график «сколько fallback'ов в секунду» с
     * разбивкой и алертить на anomaly.
     */
    public static void incrementFallbackCounter(MeterRegistry registry, String component) {
        Counter.builder("redis.fallback")
                .description("Срабатывания fallback-механизмов при недоступности Redis")
                .tag("component", component)
                .register(registry)
                .increment();
    }

    /**
     * Decorator над основным {@link CacheManager}, реализующий circuit breaker
     * на основе {@link RedisHealthService}. Когда Redis помечен как down,
     * возвращает {@link HealthAwareCache} в режиме no-op — без обращения к Redis.
     *
     * Эффект: при недоступности Redis 4 cache-операции на запрос
     * (get/put для user-auth + users-me) выполняются мгновенно вместо timeout
     * 300мс на каждой → суммарно ~1.2 сек экономии на запрос.
     */
    static class HealthAwareCacheManager implements CacheManager {

        private final CacheManager delegate;
        private final RedisHealthService health;
        private final MeterRegistry meterRegistry;

        HealthAwareCacheManager(CacheManager delegate, RedisHealthService health, MeterRegistry meterRegistry) {
            this.delegate = delegate;
            this.health = health;
            this.meterRegistry = meterRegistry;
        }

        @Override
        public Cache getCache(String name) {
            Cache underlying = delegate.getCache(name);
            return underlying == null ? null : new HealthAwareCache(underlying, health, meterRegistry);
        }

        @Override
        public Collection<String> getCacheNames() {
            return delegate.getCacheNames();
        }
    }

    /**
     * Decorator над {@link Cache}, который при {@code !health.isRedisHealthy()}
     * пропускает обращения к Redis: {@code get} → null (cache miss → метод выполнится),
     * {@code put}/{@code evict}/{@code clear} → no-op.
     *
     * При cache miss из-за circuit breaker инкрементируется counter
     * {@code redis.fallback{component=cache}}.
     */
    static class HealthAwareCache implements Cache {

        private final Cache delegate;
        private final RedisHealthService health;
        private final MeterRegistry meterRegistry;

        HealthAwareCache(Cache delegate, RedisHealthService health, MeterRegistry meterRegistry) {
            this.delegate = delegate;
            this.health = health;
            this.meterRegistry = meterRegistry;
        }

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public Object getNativeCache() {
            return delegate.getNativeCache();
        }

        @Override
        public ValueWrapper get(Object key) {
            if (!health.isRedisHealthy()) {
                incrementFallbackCounter(meterRegistry, "cache");
                return null;
            }
            return delegate.get(key);
        }

        @Override
        public <T> T get(Object key, Class<T> type) {
            if (!health.isRedisHealthy()) {
                incrementFallbackCounter(meterRegistry, "cache");
                return null;
            }
            return delegate.get(key, type);
        }

        @Override
        public <T> T get(Object key, Callable<T> valueLoader) {
            if (!health.isRedisHealthy()) {
                incrementFallbackCounter(meterRegistry, "cache");
                try {
                    return valueLoader.call();
                } catch (Exception ex) {
                    throw new ValueRetrievalException(key, valueLoader, ex);
                }
            }
            return delegate.get(key, valueLoader);
        }

        @Override
        public void put(Object key, Object value) {
            if (!health.isRedisHealthy()) {
                return;
            }
            delegate.put(key, value);
        }

        @Override
        public ValueWrapper putIfAbsent(Object key, Object value) {
            if (!health.isRedisHealthy()) {
                return null;
            }
            return delegate.putIfAbsent(key, value);
        }

        @Override
        public void evict(Object key) {
            if (!health.isRedisHealthy()) {
                return;
            }
            delegate.evict(key);
        }

        @Override
        public boolean evictIfPresent(Object key) {
            if (!health.isRedisHealthy()) {
                return false;
            }
            return delegate.evictIfPresent(key);
        }

        @Override
        public void clear() {
            if (!health.isRedisHealthy()) {
                return;
            }
            delegate.clear();
        }

        @Override
        public boolean invalidate() {
            if (!health.isRedisHealthy()) {
                return false;
            }
            return delegate.invalidate();
        }
    }
}
