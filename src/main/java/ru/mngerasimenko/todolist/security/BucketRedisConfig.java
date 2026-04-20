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
        RedisURI.Builder uriBuilder = RedisURI.builder()
                .withHost(props.getHost())
                .withPort(props.getPort())
                .withDatabase(props.getDatabase());
        if (props.getPassword() != null && !props.getPassword().isEmpty()) {
            uriBuilder.withPassword(props.getPassword().toCharArray());
        }
        return RedisClient.create(uriBuilder.build());
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
