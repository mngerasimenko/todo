package ru.mngerasimenko.todolist.security.jwt;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты JwtTokenProvider — проверка claim "type", валидации access-токенов.
 */
class JwtTokenProviderTest {

    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties();
        // 256-bit тестовый ключ (base64)
        props.setSecret(Base64.getEncoder().encodeToString(
                "test-secret-key-for-jwt-testing-256-bits!!".getBytes()));
        props.setAccessTokenExpiration(3600000L); // 1 час
        props.setRefreshTokenExpiration(604800000L); // 7 дней
        jwtTokenProvider = new JwtTokenProvider(props);
    }

    @Test
    void validateAccessToken_AcceptsAccessToken() {
        String token = jwtTokenProvider.generateAccessToken("user@test.com");

        assertTrue(jwtTokenProvider.validateAccessToken(token));
    }

    @Test
    void validateAccessToken_RejectsInvalidToken() {
        assertFalse(jwtTokenProvider.validateAccessToken("invalid.jwt.token"));
    }

    @Test
    void validateToken_AcceptsAccessToken() {
        String token = jwtTokenProvider.generateAccessToken("user@test.com");

        assertTrue(jwtTokenProvider.validateToken(token));
    }

    @Test
    void validateToken_RejectsInvalidToken() {
        assertFalse(jwtTokenProvider.validateToken("invalid.jwt.token"));
    }

    @Test
    void getUsernameFromToken_ReturnsCorrectEmail() {
        String token = jwtTokenProvider.generateAccessToken("user@test.com");

        assertEquals("user@test.com", jwtTokenProvider.getUsernameFromToken(token));
    }

    @Test
    void getExpirationFromToken_ReturnsFutureInstant() {
        String token = jwtTokenProvider.generateAccessToken("user@test.com");

        Instant expiration = jwtTokenProvider.getExpirationFromToken(token);
        assertTrue(expiration.isAfter(Instant.now()));
    }

    @Test
    void parseExpiredToken_MasksEmailInWarnLog() {
        // Провайдер с отрицательным сроком → токен истекает мгновенно
        JwtProperties expiredProps = new JwtProperties();
        expiredProps.setSecret(Base64.getEncoder().encodeToString(
                "test-secret-key-for-jwt-testing-256-bits!!".getBytes()));
        expiredProps.setAccessTokenExpiration(-1000L);
        JwtTokenProvider expiredProvider = new JwtTokenProvider(expiredProps);
        String expiredToken = expiredProvider.generateAccessToken("user@test.com");

        Logger logger = (Logger) LoggerFactory.getLogger(JwtTokenProvider.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            // parseClaims ловит ExpiredJwtException и пишет WARN
            assertFalse(expiredProvider.validateToken(expiredToken));
        } finally {
            logger.detachAppender(appender);
        }

        String logged = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("истёк"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Ожидался WARN об истёкшем токене"));
        assertTrue(logged.contains("us***@test.com"),
                "email должен быть замаскирован, а лог: " + logged);
        assertFalse(logged.contains("user@test.com"),
                "полный email не должен попадать в лог, а лог: " + logged);
    }
}
