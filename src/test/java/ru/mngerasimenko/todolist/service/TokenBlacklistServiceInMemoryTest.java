package ru.mngerasimenko.todolist.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты TokenBlacklistServiceInMemory — blacklist, проверка, очистка.
 */
class TokenBlacklistServiceInMemoryTest {

    private TokenBlacklistServiceInMemory blacklistService;

    @BeforeEach
    void setUp() {
        blacklistService = new TokenBlacklistServiceInMemory();
    }

    @Test
    void blacklistAndCheck_TokenIsBlacklisted() {
        String token = "test-access-token-123";

        blacklistService.blacklistAccessToken(token, Instant.now().plusSeconds(3600));

        assertTrue(blacklistService.isBlacklisted(token));
    }

    @Test
    void isBlacklisted_NonBlacklistedToken_ReturnsFalse() {
        assertFalse(blacklistService.isBlacklisted("unknown-token"));
    }

    @Test
    void isBlacklisted_ExpiredToken_ReturnsFalseAndRemoves() {
        String token = "expired-token";

        blacklistService.blacklistAccessToken(token, Instant.now().minusSeconds(1));

        assertFalse(blacklistService.isBlacklisted(token));
    }

    @Test
    void evictExpired_RemovesOnlyExpiredEntries() {
        blacklistService.blacklistAccessToken("active", Instant.now().plusSeconds(3600));
        blacklistService.blacklistAccessToken("expired", Instant.now().minusSeconds(1));

        blacklistService.evictExpired();

        assertTrue(blacklistService.isBlacklisted("active"));
        assertFalse(blacklistService.isBlacklisted("expired"));
    }

    @Test
    void differentTokens_IndependentBlacklist() {
        blacklistService.blacklistAccessToken("token-a", Instant.now().plusSeconds(3600));

        assertTrue(blacklistService.isBlacklisted("token-a"));
        assertFalse(blacklistService.isBlacklisted("token-b"));
    }
}
