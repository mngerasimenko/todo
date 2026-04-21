package ru.mngerasimenko.todolist.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.ObjectMapper.DefaultTyping;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Конфигурация Spring Cache через Redis (spring-boot-starter-cache + spring-data-redis).
 *
 * Кэшируются hot-paths с коротким TTL:
 * - {@code users-me} (60 сек) — ответ GET /api/users/me, ключ = email пользователя.
 * - {@code task-lists} (60 сек) — ответ GET /api/lists, ключ = userId.
 *
 * Runtime-отключение — через {@link ru.mngerasimenko.todolist.featureflags.FeatureFlag#RESPONSE_CACHE}
 * (SpEL-condition в @Cacheable-методах).
 */
@Configuration
@EnableCaching
public class RedisCacheConfig {

    public static final String USERS_ME = "users-me";
    public static final String TASK_LISTS = "task-lists";

    /**
     * SpEL-условие для @Cacheable — проверяет feature flag RESPONSE_CACHE.
     * Вынесено в константу, чтобы повторно использовать в нескольких сервисах.
     */
    public static final String CACHE_CONDITION =
            "@featureFlagStore.isEnabled(T(ru.mngerasimenko.todolist.featureflags.FeatureFlag).RESPONSE_CACHE)";

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(jsonSerializer()));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(base)
                .withCacheConfiguration(USERS_ME, base.entryTtl(Duration.ofSeconds(60)))
                .withCacheConfiguration(TASK_LISTS, base.entryTtl(Duration.ofSeconds(60)))
                .build();
    }

    /**
     * JSON-сериализатор с whitelisted polymorphic typing — сохраняет конкретный
     * тип DTO при записи в Redis, чтобы при чтении корректно десериализовалось
     * даже для коллекций (List<ListResponse> и т.д.).
     *
     * Whitelist ограничен нашими пакетами + стандартными JDK — блокирует
     * известные Jackson gadget-chains (Logback, C3P0, Spring MVEL) на случай
     * компрометации Redis.
     */
    private GenericJackson2JsonRedisSerializer jsonSerializer() {
        ObjectMapper mapper = new ObjectMapper();
        BasicPolymorphicTypeValidator validator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("ru.mngerasimenko.todolist.dto")
                .allowIfSubType("java.util")
                .allowIfSubType("java.time")
                .allowIfSubType("java.lang")
                .build();
        mapper.activateDefaultTyping(validator, DefaultTyping.NON_FINAL);
        return new GenericJackson2JsonRedisSerializer(mapper);
    }
}
