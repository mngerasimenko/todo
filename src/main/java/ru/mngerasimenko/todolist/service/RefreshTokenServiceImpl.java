package ru.mngerasimenko.todolist.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mngerasimenko.todolist.model.RefreshToken;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.repository.RefreshTokenRepository;
import ru.mngerasimenko.todolist.repository.UserRepository;
import ru.mngerasimenko.todolist.security.jwt.JwtProperties;
import ru.mngerasimenko.todolist.util.TokenUtils;
import static ru.mngerasimenko.todolist.util.LogUtils.maskEmail;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Реализация сервиса refresh-токенов с ротацией и reuse detection.
 * Refresh-токен — opaque UUID, хранится в БД как SHA-256 хеш.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenServiceImpl implements RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final JwtProperties jwtProperties;

    @Override
    @Transactional
    public String createRefreshToken(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден: " + userId));

        String rawToken = UUID.randomUUID().toString();
        String tokenHash = TokenUtils.sha256(rawToken);
        UUID familyId = UUID.randomUUID();
        LocalDateTime expiresAt = LocalDateTime.now()
                .plusSeconds(jwtProperties.getRefreshTokenExpiration() / 1000);

        RefreshToken refreshToken = new RefreshToken(tokenHash, user, familyId, expiresAt);
        refreshTokenRepository.saveAndFlush(refreshToken);

        log.info("Создан refresh-токен для пользователя: {}", maskEmail(user.getEmail()));
        return rawToken;
    }

    @Override
    @Transactional
    public RefreshTokenRotationResult rotateRefreshToken(String rawToken) {
        String tokenHash = TokenUtils.sha256(rawToken);

        RefreshToken existing = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> {
                    log.warn("Refresh-токен не найден в БД");
                    return new BadCredentialsException("Невалидный refresh-токен");
                });

        // Проверка истечения
        if (existing.getExpiresAt().isBefore(LocalDateTime.now())) {
            log.warn("Refresh-токен истёк для пользователя: {}", maskEmail(existing.getUser().getEmail()));
            throw new BadCredentialsException("Refresh-токен истёк");
        }

        // Reuse detection: если токен уже отозван — компрометация семьи
        if (existing.isRevoked()) {
            log.warn("REUSE DETECTION: повторное использование отозванного refresh-токена! " +
                    "Пользователь: {}, familyId: {}. Отзываем всю семью.",
                    maskEmail(existing.getUser().getEmail()), existing.getFamilyId());
            refreshTokenRepository.revokeFamily(existing.getFamilyId());
            throw new BadCredentialsException("Refresh-токен отозван. Войдите заново.");
        }

        // Отзываем старый токен
        existing.setRevoked(true);
        refreshTokenRepository.saveAndFlush(existing);

        // Создаём новый токен с тем же family_id
        String newRawToken = UUID.randomUUID().toString();
        String newTokenHash = TokenUtils.sha256(newRawToken);
        LocalDateTime expiresAt = LocalDateTime.now()
                .plusSeconds(jwtProperties.getRefreshTokenExpiration() / 1000);

        RefreshToken newToken = new RefreshToken(
                newTokenHash, existing.getUser(), existing.getFamilyId(), expiresAt);
        refreshTokenRepository.saveAndFlush(newToken);

        String email = existing.getUser().getEmail();
        log.info("Ротация refresh-токена для пользователя: {}", maskEmail(email));

        return new RefreshTokenRotationResult(newRawToken, email);
    }

    @Override
    @Transactional
    public void revokeByRawToken(String rawToken) {
        String tokenHash = TokenUtils.sha256(rawToken);
        refreshTokenRepository.findByTokenHash(tokenHash)
                .ifPresent(token -> {
                    token.setRevoked(true);
                    refreshTokenRepository.saveAndFlush(token);
                    log.info("Refresh-токен отозван для пользователя: {}", maskEmail(token.getUser().getEmail()));
                });
    }

    @Override
    @Transactional
    public void revokeAllForUser(Long userId) {
        refreshTokenRepository.deleteByUserId(userId);
        log.info("Все refresh-токены удалены для пользователя: id={}", userId);
    }
}
