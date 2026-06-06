package ru.mngerasimenko.todolist.scheduler;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.mngerasimenko.todolist.service.PushNotificationService;

/**
 * Фоновая проверка Firebase каждые 15 минут.
 * Результат кешируется в PushNotificationServiceImpl — /api/status отдаёт мгновенно.
 * Активен только при включённом Firebase.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.firebase.enabled", havingValue = "true", matchIfMissing = false)
public class FirebaseHealthScheduler {

    private final PushNotificationService pushNotificationService;

    /**
     * Синхронный warmup при старте приложения: устраняет окно false-positive
     * «Firebase недоступен» между стартом и первым тиком (initialDelay 15 с),
     * в которое попадал внешний монитор 2026-05-17 после prod-deploy.
     */
    @PostConstruct
    void warmup() {
        try {
            pushNotificationService.checkFirebaseHealth();
        } catch (Exception e) {
            log.warn("Firebase warmup failed; scheduled check will retry shortly", e);
        }
    }

    @Scheduled(fixedRate = 900_000, initialDelay = 15_000)
    public void checkFirebaseHealth() {
        pushNotificationService.checkFirebaseHealth();
    }
}
