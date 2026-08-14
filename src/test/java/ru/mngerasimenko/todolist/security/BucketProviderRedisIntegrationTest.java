package ru.mngerasimenko.todolist.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционный тест RedisBucketProvider с настоящим Redis через TestContainers.
 * Главное проверяемое свойство — состояние bucket'а переживает пересоздание ProxyManager
 * (rate limit не сбрасывается при рестарте приложения и общий для всех инстансов).
 * Запускается в профиле -Pintegration (@Tag("integration")).
 */
@Tag("integration")
class BucketProviderRedisIntegrationTest {

    private static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    private static RedisClient client;
    private static StatefulRedisConnection<String, byte[]> connection;

    @BeforeAll
    static void startContainer() {
        // Обход Docker Desktop на Windows — тот же, что в TokenBlacklistServiceRedisIntegrationTest.
        // Только на Windows: api.version=1.53 требует Docker Engine 29.x, а на Linux-раннере
        // демон отвергает слишком новую версию клиента (см. AbstractIntegrationTest).
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            System.setProperty("api.version", "1.53");
            if (System.getenv("DOCKER_HOST") == null || System.getenv("DOCKER_HOST").isBlank()) {
                System.setProperty("DOCKER_HOST", "tcp://localhost:2375");
            }
        }
        redis.start();

        client = RedisClient.create(RedisURI.create(redis.getHost(), redis.getFirstMappedPort()));
        connection = client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
    }

    @AfterAll
    static void shutdown() {
        if (connection != null) connection.close();
        if (client != null) client.shutdown();
    }

    @AfterEach
    void flushRedis() {
        connection.sync().flushall();
    }

    private BucketProviderRedis createProvider() {
        LettuceBasedProxyManager<String> proxyManager = Bucket4jLettuce.casBasedBuilder(connection)
                .expirationAfterWrite(
                        ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                                Duration.ofMinutes(5)))
                .build();
        return new BucketProviderRedis(proxyManager);
    }

    private BucketConfiguration limit(int capacity, Duration period) {
        return BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(capacity)
                        .refillGreedy(capacity, period)
                        .build())
                .build();
    }

    @Test
    void bucketState_PersistsAcrossProviderInstances() {
        BucketConfiguration config = limit(5, Duration.ofMinutes(1));

        BucketProviderRedis provider1 = createProvider();
        provider1.resolveBucket("login:1.2.3.4", config).tryConsume(3);

        // Имитируем рестарт: новый ProxyManager, тот же Redis.
        BucketProviderRedis provider2 = createProvider();
        Bucket bucket2 = provider2.resolveBucket("login:1.2.3.4", config);

        assertThat(bucket2.getAvailableTokens()).isEqualTo(2L);
    }

    @Test
    void differentKeys_HaveIndependentBuckets() {
        BucketConfiguration config = limit(5, Duration.ofMinutes(1));
        BucketProviderRedis provider = createProvider();

        provider.resolveBucket("login:1.1.1.1", config).tryConsume(5);
        Bucket other = provider.resolveBucket("login:2.2.2.2", config);

        assertThat(other.getAvailableTokens()).isEqualTo(5L);
    }

    @Test
    void rateLimitExceeded_BlocksFurtherConsumption() {
        BucketConfiguration config = limit(3, Duration.ofMinutes(1));
        BucketProviderRedis provider = createProvider();

        Bucket bucket = provider.resolveBucket("login:3.3.3.3", config);
        assertThat(bucket.tryConsume(3)).isTrue();
        assertThat(bucket.tryConsume(1)).isFalse();
    }
}
