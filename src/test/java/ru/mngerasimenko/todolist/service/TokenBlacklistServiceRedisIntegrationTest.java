package ru.mngerasimenko.todolist.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import ru.mngerasimenko.todolist.util.TokenUtils;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционный тест Redis-blacklist с настоящим Redis через TestContainers.
 * Проверяет то, что unit-тесты с моками проверить не могут:
 *  — реальная запись по ключу с префиксом;
 *  — Redis действительно держит TTL и удаляет ключи сам;
 *  — cross-call consistency (записали → нашли).
 * Запускается в профиле -Pintegration (@Tag("integration")).
 */
@Tag("integration")
class TokenBlacklistServiceRedisIntegrationTest {

    private static final String PREFIX = "todo";

    private static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    private StringRedisTemplate redisTemplate;
    private TokenBlacklistServiceRedis service;

    @BeforeAll
    static void startContainer() {
        // Тот же обходной путь, что и в AbstractIntegrationTest —
        // Docker Desktop на Windows требует api.version 1.44+.
        System.setProperty("api.version", "1.53");
        if (System.getenv("DOCKER_HOST") == null || System.getenv("DOCKER_HOST").isBlank()) {
            System.setProperty("DOCKER_HOST", "tcp://localhost:2375");
        }
        redis.start();
    }

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redis.getHost(), redis.getFirstMappedPort()));
        factory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(factory);

        service = new TokenBlacklistServiceRedis(redisTemplate);
        ReflectionTestUtils.setField(service, "keyPrefix", PREFIX);
    }

    @AfterEach
    void flushRedis() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void blacklistedToken_PersistsInRedisWithExpectedKeyAndTtl() {
        String token = "real-token-42";
        Instant expiresAt = Instant.now().plusSeconds(60);

        service.blacklistAccessToken(token, expiresAt);

        String expectedKey = PREFIX + ":blacklist:" + TokenUtils.sha256(token);
        assertThat(redisTemplate.hasKey(expectedKey)).isTrue();

        Long ttlSeconds = redisTemplate.getExpire(expectedKey, TimeUnit.SECONDS);
        assertThat(ttlSeconds).isBetween(50L, 60L);

        assertThat(service.isBlacklisted(token)).isTrue();
    }

    @Test
    void expiredToken_IsNotWrittenToRedis() {
        String token = "already-expired";

        service.blacklistAccessToken(token, Instant.now().minusSeconds(1));

        String key = PREFIX + ":blacklist:" + TokenUtils.sha256(token);
        assertThat(redisTemplate.hasKey(key)).isFalse();
        assertThat(service.isBlacklisted(token)).isFalse();
    }

    @Test
    void ttlExpires_RedisAutoRemovesKey() throws InterruptedException {
        String token = "short-lived";

        service.blacklistAccessToken(token, Instant.now().plusSeconds(1));
        assertThat(service.isBlacklisted(token)).isTrue();

        // Ждём чуть дольше TTL, чтобы Redis успел удалить ключ.
        Thread.sleep(1500);

        String key = PREFIX + ":blacklist:" + TokenUtils.sha256(token);
        assertThat(redisTemplate.hasKey(key)).isFalse();
        assertThat(service.isBlacklisted(token)).isFalse();
    }

    @Test
    void multipleTokens_StoredIndependently() {
        service.blacklistAccessToken("token-a", Instant.now().plusSeconds(60));
        service.blacklistAccessToken("token-b", Instant.now().plusSeconds(60));

        assertThat(service.isBlacklisted("token-a")).isTrue();
        assertThat(service.isBlacklisted("token-b")).isTrue();
        assertThat(service.isBlacklisted("token-c")).isFalse();
    }

    @Test
    void unknownToken_IsNotBlacklisted() {
        assertThat(service.isBlacklisted("never-seen")).isFalse();
    }
}
