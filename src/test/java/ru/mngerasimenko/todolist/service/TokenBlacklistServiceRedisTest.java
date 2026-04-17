package ru.mngerasimenko.todolist.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import ru.mngerasimenko.todolist.util.TokenUtils;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Тесты Redis-реализации blacklist: запись с TTL, чтение,
 * graceful degradation на in-memory fallback при сбое Redis.
 */
@ExtendWith(MockitoExtension.class)
class TokenBlacklistServiceRedisTest {

    private static final String PREFIX = "todo";
    private static final String TOKEN = "test-access-token-123";
    private static final String HASH = TokenUtils.sha256(TOKEN);
    private static final String KEY = PREFIX + ":blacklist:" + HASH;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private TokenBlacklistServiceRedis service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "keyPrefix", PREFIX);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void blacklistAccessToken_WritesToRedisWithCorrectKeyAndTtl() {
        Instant expiresAt = Instant.now().plusSeconds(3600);

        service.blacklistAccessToken(TOKEN, expiresAt);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(keyCaptor.capture(), eq("1"), ttlCaptor.capture());

        assertThat(keyCaptor.getValue()).isEqualTo(KEY);
        // TTL примерно час — допускаем люфт на время выполнения теста
        assertThat(ttlCaptor.getValue())
                .isBetween(Duration.ofSeconds(3595), Duration.ofSeconds(3600));
    }

    @Test
    void blacklistAccessToken_ExpiredToken_DoesNotWriteToRedis() {
        Instant expiresAt = Instant.now().minusSeconds(1);

        service.blacklistAccessToken(TOKEN, expiresAt);

        verifyNoInteractions(valueOps);
    }

    @Test
    void isBlacklisted_RedisHasKey_ReturnsTrue() {
        when(redisTemplate.hasKey(KEY)).thenReturn(Boolean.TRUE);

        assertThat(service.isBlacklisted(TOKEN)).isTrue();
    }

    @Test
    void isBlacklisted_RedisMissAndFallbackEmpty_ReturnsFalse() {
        when(redisTemplate.hasKey(KEY)).thenReturn(Boolean.FALSE);

        assertThat(service.isBlacklisted(TOKEN)).isFalse();
    }

    @Test
    void blacklistAccessToken_RedisDown_WritesToFallback() {
        doThrow(new RedisConnectionFailureException("Redis down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));
        when(redisTemplate.hasKey(KEY)).thenReturn(Boolean.FALSE);

        service.blacklistAccessToken(TOKEN, Instant.now().plusSeconds(3600));

        // Redis пуст, но токен был записан в fallback → isBlacklisted() == true
        assertThat(service.isBlacklisted(TOKEN)).isTrue();
    }

    @Test
    void isBlacklisted_RedisThrows_ChecksFallback() {
        // Сначала кладём токен в fallback через сбой записи
        doThrow(new RedisConnectionFailureException("Redis down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));
        service.blacklistAccessToken(TOKEN, Instant.now().plusSeconds(3600));

        // Теперь Redis падает и на чтении
        when(redisTemplate.hasKey(KEY)).thenThrow(new QueryTimeoutException("timeout"));

        assertThat(service.isBlacklisted(TOKEN)).isTrue();
    }

    @Test
    void isBlacklisted_RedisMissButFallbackHit_ReturnsTrue() {
        // Кладём в fallback (имитируя прошлый сбой Redis)
        doThrow(new RedisConnectionFailureException("Redis down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));
        service.blacklistAccessToken(TOKEN, Instant.now().plusSeconds(3600));

        // Redis ожил, но у него токена нет (fallback всё ещё помнит)
        reset(valueOps);
        when(redisTemplate.hasKey(KEY)).thenReturn(Boolean.FALSE);

        assertThat(service.isBlacklisted(TOKEN)).isTrue();
    }

    @Test
    void differentTokens_UseDifferentKeys() {
        service.blacklistAccessToken("token-a", Instant.now().plusSeconds(3600));
        service.blacklistAccessToken("token-b", Instant.now().plusSeconds(3600));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps, org.mockito.Mockito.times(2))
                .set(keyCaptor.capture(), anyString(), any(Duration.class));

        assertThat(keyCaptor.getAllValues())
                .containsExactly(
                        PREFIX + ":blacklist:" + TokenUtils.sha256("token-a"),
                        PREFIX + ":blacklist:" + TokenUtils.sha256("token-b"))
                .doesNotHaveDuplicates();
    }
}
