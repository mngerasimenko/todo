package ru.mngerasimenko.todolist.config;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.dto.list.ListResponse;

import java.time.Duration;
import java.util.List;

/**
 * Конфигурация Spring Cache через Redis (spring-boot-starter-cache + spring-data-redis).
 *
 * Кэшируются hot-paths с коротким TTL:
 * - {@code users-me} (60 сек) — ответ GET /api/users/me, ключ = email пользователя.
 * - {@code task-lists} (60 сек) — ответ GET /api/lists, ключ = userId.
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
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory,
                                          ObjectMapper appObjectMapper) {
        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .disableCachingNullValues()
                .serializeKeysWith(SerializationPair.fromSerializer(new StringRedisSerializer()));

        // Отдельный serializer для каждого кэша — по фиксированному типу.
        RedisSerializer<UserDto> userDtoSerializer =
                new Jackson2JsonRedisSerializer<>(appObjectMapper, UserDto.class);

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
                .build();
    }
}
