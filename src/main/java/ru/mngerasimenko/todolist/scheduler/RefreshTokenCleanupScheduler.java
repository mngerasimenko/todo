package ru.mngerasimenko.todolist.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.mngerasimenko.todolist.repository.RefreshTokenRepository;

import java.time.LocalDateTime;

/**
 * Периодическая очистка истёкших refresh-токенов.
 * Запускается каждый час.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenCleanupScheduler {

    private final RefreshTokenRepository refreshTokenRepository;

    @Scheduled(fixedRate = 3600000) // каждый час
    @Transactional
    public void cleanupExpiredTokens() {
        refreshTokenRepository.deleteExpired(LocalDateTime.now());
    }
}
