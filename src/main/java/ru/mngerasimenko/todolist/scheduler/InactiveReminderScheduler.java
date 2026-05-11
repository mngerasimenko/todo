package ru.mngerasimenko.todolist.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.mngerasimenko.todolist.featureflags.FeatureFlag;
import ru.mngerasimenko.todolist.featureflags.FeatureFlagStore;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.service.EmailService;
import ru.mngerasimenko.todolist.service.PushNotificationService;
import ru.mngerasimenko.todolist.service.UserService;

import java.util.List;

/**
 * Ежедневное напоминание пользователям, которые не заходили в приложение 3+ дня.
 * Каждому пользователю отправляется максимум одно напоминание (трекинг через lastReminderSentAt).
 *
 * Отправляет:
 * - Push-уведомление (если есть FCM-токен)
 * - Email (если email подтверждён)
 *
 * Включение/выключение — через {@link FeatureFlag#INACTIVE_REMINDER} (runtime + env).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InactiveReminderScheduler {

    private static final int INACTIVE_DAYS = 7;

    private final UserService userService;
    private final EmailService emailService;
    private final PushNotificationService pushNotificationService;
    private final FeatureFlagStore flagStore;

    /**
     * Запускается каждый день в 10:00 по серверному времени.
     */
    @Scheduled(cron = "0 0 10 * * *")
    public void sendReminders() {
        if (!flagStore.isEnabled(FeatureFlag.INACTIVE_REMINDER)) {
            log.debug("[inactive-reminder] Scheduler отключён через feature flag");
            return;
        }

        List<User> inactiveUsers = userService.findInactiveUsersForReminder(INACTIVE_DAYS);

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
                    emailService.sendInactiveReminderEmail(
                            user.getEmail(), user.getName(), user.getId(), user.getPreferredEmailLocale());
                    sentEmails++;
                } catch (Exception e) {
                    log.warn("[inactive-reminder] Ошибка отправки email userId={}: {}", user.getId(), e.getMessage());
                }
            }

            // Отметить что напоминание отправлено
            try {
                userService.markReminderSent(user.getId());
            } catch (Exception e) {
                log.warn("[inactive-reminder] Ошибка отметки напоминания userId={}: {}", user.getId(), e.getMessage());
            }
        }

        log.info("[inactive-reminder] Напоминание отправлено: push={}, email={}, всего пользователей={}",
                sentPushes, sentEmails, inactiveUsers.size());
    }
}
