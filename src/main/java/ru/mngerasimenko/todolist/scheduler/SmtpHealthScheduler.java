package ru.mngerasimenko.todolist.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.mngerasimenko.todolist.service.EmailService;

/**
 * Фоновая проверка SMTP. Интервал по умолчанию — 60 минут, настраивается
 * через property {@code app.smtp-health-check.interval-ms}. SMTP — не критическая
 * зависимость (используется для верификации email и сброса пароля), частая
 * проверка не нужна; раз в час даёт достаточную гранулярность для алертов
 * и не создаёт шума в логах WARN при длительных инцидентах у провайдера
 * (например, плановые работы Jino 27 апр 2026 — несколько часов недоступности).
 *
 * Результат кешируется в EmailServiceImpl — /api/status отдаёт мгновенно.
 * Отключается в тестах через app.smtp-health-check.enabled=false.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.smtp-health-check.enabled", havingValue = "true", matchIfMissing = true)
public class SmtpHealthScheduler {

    private final EmailService emailService;

    @Scheduled(fixedRateString = "${app.smtp-health-check.interval-ms:3600000}", initialDelay = 10_000)
    public void checkSmtpHealth() {
        emailService.checkSmtpHealth();
    }
}
