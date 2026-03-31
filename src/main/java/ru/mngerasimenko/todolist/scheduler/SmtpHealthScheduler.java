package ru.mngerasimenko.todolist.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.mngerasimenko.todolist.service.EmailService;

/**
 * Фоновая проверка SMTP каждые 15 минут.
 * Результат кешируется в EmailServiceImpl — /api/status отдаёт мгновенно.
 * Отключается в тестах через app.smtp-health-check.enabled=false.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.smtp-health-check.enabled", havingValue = "true", matchIfMissing = true)
public class SmtpHealthScheduler {

    private final EmailService emailService;

    @Scheduled(fixedRate = 900_000, initialDelay = 10_000)
    public void checkSmtpHealth() {
        emailService.checkSmtpHealth();
    }
}
