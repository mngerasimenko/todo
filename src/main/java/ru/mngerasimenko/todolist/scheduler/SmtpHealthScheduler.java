package ru.mngerasimenko.todolist.scheduler;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@ConditionalOnProperty(name = "app.smtp-health-check.enabled", havingValue = "true", matchIfMissing = true)
public class SmtpHealthScheduler {

    private final EmailService emailService;

    /**
     * Синхронный warmup при старте приложения: без него кешированный флаг здоровья
     * SMTP инициализируется значением по умолчанию (false) до первого тика scheduler'а,
     * и внешние мониторы между стартом и initialDelay (10 с) ловят false-positive
     * «SMTP недоступен» — наблюдали 2026-05-17 после prod-deploy. Стартап-задержка
     * 1-2 с от SMTP TCP-handshake приемлема, выполняется один раз.
     */
    @PostConstruct
    void warmup() {
        try {
            emailService.checkSmtpHealth();
        } catch (Exception e) {
            log.warn("SMTP warmup failed; scheduled check will retry shortly", e);
        }
    }

    @Scheduled(fixedRateString = "${app.smtp-health-check.interval-ms:3600000}", initialDelay = 10_000)
    public void checkSmtpHealth() {
        emailService.checkSmtpHealth();
    }
}
