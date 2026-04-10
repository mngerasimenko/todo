package ru.mngerasimenko.todolist.scheduler;

import lombok.RequiredArgsConstructor;
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
@ConditionalOnProperty(name = "app.firebase.enabled", havingValue = "true", matchIfMissing = false)
public class FirebaseHealthScheduler {

    private final PushNotificationService pushNotificationService;

    @Scheduled(fixedRate = 900_000, initialDelay = 15_000)
    public void checkFirebaseHealth() {
        pushNotificationService.checkFirebaseHealth();
    }
}
