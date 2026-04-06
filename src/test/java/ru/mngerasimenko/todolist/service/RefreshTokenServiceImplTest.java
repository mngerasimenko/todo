package ru.mngerasimenko.todolist.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import ru.mngerasimenko.todolist.model.RefreshToken;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.repository.RefreshTokenRepository;
import ru.mngerasimenko.todolist.repository.UserRepository;
import ru.mngerasimenko.todolist.security.jwt.JwtProperties;
import ru.mngerasimenko.todolist.util.TokenUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Тесты RefreshTokenServiceImpl — ротация, reuse detection, создание и отзыв токенов.
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceImplTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtProperties jwtProperties;

    @InjectMocks
    private RefreshTokenServiceImpl refreshTokenService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(1L);
        testUser.setEmail("test@example.com");
        testUser.setName("testUser");
        testUser.setPassword("hashedPassword");
    }

    // ==================== CREATE REFRESH TOKEN ====================

    @Test
    void createRefreshToken_ReturnsRawToken() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(jwtProperties.getRefreshTokenExpiration()).thenReturn(604800000L);

        String rawToken = refreshTokenService.createRefreshToken(1L);

        assertNotNull(rawToken);
        assertFalse(rawToken.isEmpty());

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).saveAndFlush(captor.capture());
        RefreshToken saved = captor.getValue();
        assertEquals(TokenUtils.sha256(rawToken), saved.getTokenHash());
        assertEquals(testUser, saved.getUser());
        assertNotNull(saved.getFamilyId());
        assertFalse(saved.isRevoked());
    }

    @Test
    void createRefreshToken_UserNotFound_ThrowsException() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> refreshTokenService.createRefreshToken(99L));
    }

    // ==================== ROTATE REFRESH TOKEN ====================

    @Test
    void rotateRefreshToken_ValidToken_ReturnsNewTokenAndRevokesOld() {
        String rawToken = UUID.randomUUID().toString();
        String tokenHash = TokenUtils.sha256(rawToken);
        UUID familyId = UUID.randomUUID();

        RefreshToken existing = new RefreshToken(tokenHash, testUser, familyId,
                LocalDateTime.now().plusDays(7));
        existing.setId(1L);

        when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(existing));
        when(jwtProperties.getRefreshTokenExpiration()).thenReturn(604800000L);

        RefreshTokenService.RefreshTokenRotationResult result =
                refreshTokenService.rotateRefreshToken(rawToken);

        assertNotNull(result.newRawToken());
        assertEquals("test@example.com", result.email());
        assertTrue(existing.isRevoked());

        // Проверяем, что сохранено 2 раза: обновление старого + создание нового
        verify(refreshTokenRepository, times(2)).saveAndFlush(any(RefreshToken.class));
    }

    @Test
    void rotateRefreshToken_ExpiredToken_ThrowsBadCredentials() {
        String rawToken = UUID.randomUUID().toString();
        String tokenHash = TokenUtils.sha256(rawToken);

        RefreshToken expired = new RefreshToken(tokenHash, testUser, UUID.randomUUID(),
                LocalDateTime.now().minusHours(1));
        expired.setId(1L);

        when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(expired));

        assertThrows(BadCredentialsException.class,
                () -> refreshTokenService.rotateRefreshToken(rawToken));
    }

    @Test
    void rotateRefreshToken_RevokedToken_NoActiveToken_RevokesEntireFamilyAndThrows() {
        String rawToken = UUID.randomUUID().toString();
        String tokenHash = TokenUtils.sha256(rawToken);
        UUID familyId = UUID.randomUUID();

        RefreshToken revoked = new RefreshToken(tokenHash, testUser, familyId,
                LocalDateTime.now().plusDays(7));
        revoked.setId(1L);
        revoked.setRevoked(true);

        when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(revoked));
        when(refreshTokenRepository.findActiveFamilyToken(eq(familyId), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());

        assertThrows(BadCredentialsException.class,
                () -> refreshTokenService.rotateRefreshToken(rawToken));

        // Reuse detection: нет активного токена — вся семья отозвана
        verify(refreshTokenRepository).revokeFamily(familyId);
    }

    @Test
    void rotateRefreshToken_RevokedToken_WithActiveToken_RotatesActiveInstead() {
        String rawToken = UUID.randomUUID().toString();
        String tokenHash = TokenUtils.sha256(rawToken);
        UUID familyId = UUID.randomUUID();

        // Отозванный токен (старый, использованный конкурентным запросом)
        RefreshToken revoked = new RefreshToken(tokenHash, testUser, familyId,
                LocalDateTime.now().plusDays(7));
        revoked.setId(1L);
        revoked.setRevoked(true);

        // Активный токен (создан предыдущей ротацией)
        RefreshToken active = new RefreshToken("activeHash", testUser, familyId,
                LocalDateTime.now().plusDays(7));
        active.setId(2L);

        when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(revoked));
        when(refreshTokenRepository.findActiveFamilyToken(eq(familyId), any(LocalDateTime.class)))
                .thenReturn(Optional.of(active));
        when(jwtProperties.getRefreshTokenExpiration()).thenReturn(604800000L);

        RefreshTokenService.RefreshTokenRotationResult result =
                refreshTokenService.rotateRefreshToken(rawToken);

        // Конкурентный запрос обработан: ротирован активный токен вместо блокировки
        assertNotNull(result.newRawToken());
        assertEquals("test@example.com", result.email());
        assertTrue(active.isRevoked());

        // Семья НЕ заблокирована
        verify(refreshTokenRepository, never()).revokeFamily(any());

        // Сохранено 2 раза: обновление активного + создание нового
        verify(refreshTokenRepository, times(2)).saveAndFlush(any(RefreshToken.class));
    }

    @Test
    void rotateRefreshToken_TokenNotFound_ThrowsBadCredentials() {
        String rawToken = UUID.randomUUID().toString();
        String tokenHash = TokenUtils.sha256(rawToken);

        when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.empty());

        assertThrows(BadCredentialsException.class,
                () -> refreshTokenService.rotateRefreshToken(rawToken));
    }

    @Test
    void rotateRefreshToken_NewTokenHasSameFamilyId() {
        String rawToken = UUID.randomUUID().toString();
        String tokenHash = TokenUtils.sha256(rawToken);
        UUID familyId = UUID.randomUUID();

        RefreshToken existing = new RefreshToken(tokenHash, testUser, familyId,
                LocalDateTime.now().plusDays(7));
        existing.setId(1L);

        when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(existing));
        when(jwtProperties.getRefreshTokenExpiration()).thenReturn(604800000L);

        refreshTokenService.rotateRefreshToken(rawToken);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository, times(2)).saveAndFlush(captor.capture());

        // Второй вызов — новый токен с тем же familyId
        RefreshToken newToken = captor.getAllValues().get(1);
        assertEquals(familyId, newToken.getFamilyId());
        assertFalse(newToken.isRevoked());
    }

    // ==================== REVOKE ====================

    @Test
    void revokeByRawToken_ExistingToken_MarksRevoked() {
        String rawToken = UUID.randomUUID().toString();
        String tokenHash = TokenUtils.sha256(rawToken);

        RefreshToken token = new RefreshToken(tokenHash, testUser, UUID.randomUUID(),
                LocalDateTime.now().plusDays(7));

        when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(token));

        refreshTokenService.revokeByRawToken(rawToken);

        assertTrue(token.isRevoked());
        verify(refreshTokenRepository).saveAndFlush(token);
    }

    @Test
    void revokeByRawToken_NonExistingToken_DoesNothing() {
        String rawToken = UUID.randomUUID().toString();
        String tokenHash = TokenUtils.sha256(rawToken);

        when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.empty());

        refreshTokenService.revokeByRawToken(rawToken);

        verify(refreshTokenRepository, never()).saveAndFlush(any());
    }

    @Test
    void revokeAllForUser_DeletesAllTokens() {
        refreshTokenService.revokeAllForUser(1L);

        verify(refreshTokenRepository).deleteByUserId(1L);
    }
}
