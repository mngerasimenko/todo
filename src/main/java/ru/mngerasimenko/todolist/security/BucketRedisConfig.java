package ru.mngerasimenko.todolist.security;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Конфигурация Bucket4j для распределённого rate limiting через Redis.
 * Активна только при rate-limit.storage=redis.
 * Создаёт отдельное TCP-соединение к тому же Redis-инстансу, что используется для token blacklist,
 * но со своим кодеком {@code <String, byte[]>}, требуемым Bucket4j.
 */
@Configuration
@ConditionalOnProperty(name = "rate-limit.storage", havingValue = "redis")
public class BucketRedisConfig {

    @Bean(destroyMethod = "shutdown")
    public RedisClient bucket4jRedisClient(RedisProperties props) {
        // Fail-fast timeout 1 секунда — если Redis недоступен, RateLimitFilter
        // быстро поймает исключение и переключится на in-memory fallback.
        // Без явного timeout Lettuce использует дефолт 60 сек: каждый запрос
        // во время сбоя Redis блокирует Tomcat-поток на минуту, и пул
        // (server.tomcat.threads.max=200) забивается за ~20 сек при traffic 10 req/s.
        Duration commandTimeout = Duration.ofSeconds(1);
        RedisURI.Builder uriBuilder = RedisURI.builder()
                .withHost(props.getHost())
                .withPort(props.getPort())
                .withDatabase(props.getDatabase())
                .withTimeout(commandTimeout);
        if (props.getPassword() != null && !props.getPassword().isEmpty()) {
            uriBuilder.withPassword(props.getPassword().toCharArray());
        }
        return RedisClient.create(uriBuilder.build());
    }

    /**
     * In-memory fallback для Bucket4j на случай недоступности Redis.
     *
     * Активен только в Redis-режиме (rate-limit.storage=redis), создаётся ЗАЕДНО с
     * {@link BucketProviderRedis}. {@link RateLimitFilter} инжектит его как Optional
     * и переключается на него при {@code RedisCommandTimeoutException} в hot path.
     *
     * Тип bean'а — {@link BucketProviderInMemory}, поэтому существующий
     * {@code RateLimitCleanupScheduler} (с {@code @ConditionalOnBean(BucketProviderInMemory.class)})
     * автоматически активируется и периодически чистит протухшие fallback-bucket'ы.
     */
    @Bean
    public BucketProviderInMemory bucket4jInMemoryFallback() {
        return new BucketProviderInMemory();
    }

    @Bean(destroyMethod = "close")
    public StatefulRedisConnection<String, byte[]> bucket4jRedisConnection(RedisClient client) {
        RedisCodec<String, byte[]> codec = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);
        return client.connect(codec);
    }

    @Bean
    public LettuceBasedProxyManager<String> bucket4jProxyManager(
            StatefulRedisConnection<String, byte[]> connection) {
        return Bucket4jLettuce.casBasedBuilder(connection)
                .expirationAfterWrite(
                        ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                                Duration.ofHours(2)
                        )
                )
                .build();
    }
}
