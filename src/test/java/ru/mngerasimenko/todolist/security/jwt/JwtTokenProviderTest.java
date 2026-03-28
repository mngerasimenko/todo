package ru.mngerasimenko.todolist.security.jwt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
}
