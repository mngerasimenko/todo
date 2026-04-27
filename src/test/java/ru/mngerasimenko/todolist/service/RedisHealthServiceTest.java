package ru.mngerasimenko.todolist.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты для RedisHealthService.
 * RedisConnectionFactory замокирован — реальный Redis не нужен.
 */
@ExtendWith(MockitoExtension.class)
class RedisHealthServiceTest {

    @Mock
    private RedisConnectionFactory connectionFactory;

    @Mock
    private RedisConnection connection;

    private RedisHealthService service;

    @BeforeEach
    void setUp() {
        service = new RedisHealthService(connectionFactory);
    }

    @Test
    void isRedisHealthy_DefaultsToFalse_BeforeFirstCheck() {
        assertThat(service.isRedisHealthy()).isFalse();
    }

    @Test
    void checkRedisHealth_PingPong_SetsHealthyTrue() {
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("PONG");

        service.checkRedisHealth();

        assertThat(service.isRedisHealthy()).isTrue();
        verify(connection).close();
    }

    @Test
    void checkRedisHealth_PingThrows_SetsHealthyFalse() {
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenThrow(new RedisConnectionFailureException("connection refused"));

        service.checkRedisHealth();

        assertThat(service.isRedisHealthy()).isFalse();
    }

    @Test
    void checkRedisHealth_GetConnectionThrows_SetsHealthyFalse() {
        when(connectionFactory.getConnection()).thenThrow(new RedisConnectionFailureException("pool exhausted"));

        service.checkRedisHealth();

        assertThat(service.isRedisHealthy()).isFalse();
    }

    @Test
    void checkRedisHealth_PingNonPong_SetsHealthyFalse() {
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("UNEXPECTED");

        service.checkRedisHealth();

        assertThat(service.isRedisHealthy()).isFalse();
    }

    @Test
    void checkRedisHealth_RecoversAfterFailure() {
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.ping())
                .thenThrow(new RedisConnectionFailureException("down"))
                .thenReturn("PONG");

        service.checkRedisHealth();
        assertThat(service.isRedisHealthy()).isFalse();

        service.checkRedisHealth();
        assertThat(service.isRedisHealthy()).isTrue();
    }

    @Test
    void markUnhealthy_SetsCacheToFalse_WithoutPing() {
        // Сначала переведём в healthy через успешный PING
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("PONG");
        service.checkRedisHealth();
        assertThat(service.isRedisHealthy()).isTrue();

        // markUnhealthy() переключает флаг сразу, без обращения к Redis
        service.markUnhealthy();

        assertThat(service.isRedisHealthy()).isFalse();
    }

    @Test
    void markUnhealthy_BeforeAnyCheck_RemainsFalse() {
        // Изначальное состояние — false; markUnhealthy() оставляет его таким же
        assertThat(service.isRedisHealthy()).isFalse();
        service.markUnhealthy();
        assertThat(service.isRedisHealthy()).isFalse();
    }
}
