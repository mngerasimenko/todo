package ru.mngerasimenko.todolist.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.mngerasimenko.todolist.repository.InviteTokenRepository;

import java.time.LocalDateTime;

/**
 * Периодическая очистка истёкших токенов приглашений.
 * Запускается каждый час.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InviteTokenCleanupScheduler {

    private final InviteTokenRepository inviteTokenRepository;

    @Scheduled(fixedRate = 3600000) // каждый час
    @Transactional
    public void cleanupExpiredTokens() {
        inviteTokenRepository.deleteExpired(LocalDateTime.now());
    }
}
