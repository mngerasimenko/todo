package ru.mngerasimenko.todolist.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.mngerasimenko.todolist.featureflags.FeatureFlag;
import ru.mngerasimenko.todolist.featureflags.FeatureFlagStore;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.service.PushNotificationService;
import ru.mngerasimenko.todolist.service.UserService;

import java.util.List;

/**
 * 3-дневное onboarding-напоминание новым пользователям, не возвращавшимся в приложение
 * с момента регистрации (Phase 3.3).
 * <p>
 * Один раз на пользователя (флаг {@code onboarding_reminder_sent}). После этого
 * пользователь подхватывается основным {@link InactiveReminderScheduler} на 7-й день.
 * <p>
 * Email-канал добавляется в следующем коммите (template + footer-link + unsubscribe-token).
 * Сейчас scheduler шлёт только push.
 * <p>
 * Включение/выключение — через {@link FeatureFlag#ONBOARDING_REMINDER}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OnboardingReminderScheduler {

    private static final int ONBOARDING_DAYS = 3;

    private final UserService userService;
    private final PushNotificationService pushNotificationService;
    private final FeatureFlagStore flagStore;

    /**
     * Запускается каждый день в 11:00 UTC (на час позже {@link InactiveReminderScheduler},
     * чтобы разнести нагрузку на email/FCM).
     */
    @Scheduled(cron = "0 0 11 * * *")
    public void sendOnboardingReminders() {
        if (!flagStore.isEnabled(FeatureFlag.ONBOARDING_REMINDER)) {
            log.debug("[onboarding-reminder] Scheduler отключён через feature flag");
            return;
        }

        List<User> candidates = userService.findOnboardingReminderCandidates(ONBOARDING_DAYS);
        if (candidates.isEmpty()) {
            log.info("[onboarding-reminder] Нет кандидатов на 3-дневное напоминание");
            return;
        }

        int sentPushes = 0;
        for (User user : candidates) {
            try {
                pushNotificationService.sendOnboardingReminderPush(user.getId(), user.getName());
                sentPushes++;
            } catch (Exception e) {
                log.warn("[onboarding-reminder] Ошибка отправки push userId={}: {}",
                        user.getId(), e.getMessage());
            }

            // TODO commit 4: добавить sendOnboardingReminderEmail с footer-link + token generation.
            //   Сейчас scheduler шлёт только push — без email юзер увидит напоминание лишь если
            //   у него есть FCM-токен. После commit 4 пайплайн станет push + email.

            try {
                userService.markOnboardingReminderSent(user.getId());
            } catch (Exception e) {
                log.warn("[onboarding-reminder] Ошибка отметки onboarding-reminder userId={}: {}",
                        user.getId(), e.getMessage());
            }
        }

        log.info("[onboarding-reminder] Onboarding-напоминание отправлено: push={}, всего пользователей={}",
                sentPushes, candidates.size());
    }
}
