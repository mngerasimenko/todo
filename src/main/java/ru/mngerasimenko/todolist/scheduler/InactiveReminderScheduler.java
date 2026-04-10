package ru.mngerasimenko.todolist.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.repository.UserRepository;
import ru.mngerasimenko.todolist.service.EmailService;
import ru.mngerasimenko.todolist.service.PushNotificationService;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Ежедневное напоминание пользователям, которые не заходили в приложение 3+ дня.
 * Каждому пользователю отправляется максимум одно напоминание (трекинг через lastReminderSentAt).
 *
 * Отправляет:
 * - Push-уведомление (если есть FCM-токен)
 * - Email (если email подтверждён)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InactiveReminderScheduler {

    private final UserRepository userRepository;
    private final EmailService emailService;
    private final PushNotificationService pushNotificationService;

    /**
     * Запускается каждый день в 10:00 по серверному времени.
     */
    @Scheduled(cron = "0 0 10 * * *")
    @Transactional
    public void sendReminders() {
        LocalDateTime inactiveSince = LocalDateTime.now().minusDays(3);
        // Не отправлять повторно тем, кому уже отправляли после их последней активности
        LocalDateTime reminderCutoff = inactiveSince;

        List<User> inactiveUsers = userRepository.findInactiveUsersForReminder(inactiveSince, reminderCutoff);

        if (inactiveUsers.isEmpty()) {
            log.info("[inactive-reminder] Нет неактивных пользователей для напоминания");
            return;
        }

        int sentEmails = 0;
        int sentPushes = 0;

        for (User user : inactiveUsers) {
            // Push — отправляем всем у кого есть токен
            try {
                pushNotificationService.sendInactiveReminderPush(user.getId(), user.getName());
                sentPushes++;
            } catch (Exception e) {
                log.warn("[inactive-reminder] Ошибка отправки push userId={}: {}", user.getId(), e.getMessage());
            }

            // Email — только если email подтверждён
            if (user.isEmailVerified()) {
                try {
                    emailService.sendInactiveReminderEmail(user.getEmail(), user.getName());
                    sentEmails++;
                } catch (Exception e) {
                    log.warn("[inactive-reminder] Ошибка отправки email userId={}: {}", user.getId(), e.getMessage());
                }
            }

            // Отметить что напоминание отправлено
            user.setLastReminderSentAt(LocalDateTime.now());
        }

        log.info("[inactive-reminder] Напоминание отправлено: push={}, email={}, всего пользователей={}",
                sentPushes, sentEmails, inactiveUsers.size());
    }
}
