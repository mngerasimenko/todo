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
 * 3-дневное onboarding-напоминание новым пользователям, не возвращавшимся в приложение
 * с момента регистрации (Phase 3.3).
 * <p>
 * Один раз на пользователя (флаг {@code onboarding_reminder_sent}). После этого
 * пользователь подхватывается основным {@link InactiveReminderScheduler} на 7-й день.
 * <p>
 * Шлёт push (если у юзера есть FCM-токен) и email (если email подтверждён).
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
    private final EmailService emailService;
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
        int sentEmails = 0;
        for (User user : candidates) {
            try {
                pushNotificationService.sendOnboardingReminderPush(user.getId(), user.getName());
                sentPushes++;
            } catch (Exception e) {
                log.warn("[onboarding-reminder] Ошибка отправки push userId={}: {}",
                        user.getId(), e.getMessage());
            }

            // Email — только если email подтверждён. Query из findOnboardingReminderCandidates
            // уже фильтрует по emailVerified=true, но проверяем здесь для defensive (если
            // кто-то изменит query в будущем).
            if (user.isEmailVerified()) {
                String unsubToken = null;
                try {
                    unsubToken = userService.issueUnsubscribeToken(user.getId());
                } catch (Exception e) {
                    log.warn("[onboarding-reminder] Не удалось сгенерировать unsubscribe-токен userId={}: {}",
                            user.getId(), e.getMessage());
                }
                try {
                    emailService.sendOnboardingReminderEmail(
                            user.getEmail(), user.getName(), user.getId(),
                            user.getPreferredEmailLocale(), unsubToken);
                    sentEmails++;
                } catch (Exception e) {
                    log.warn("[onboarding-reminder] Ошибка отправки email userId={}: {}",
                            user.getId(), e.getMessage());
                }
            }

            try {
                userService.markOnboardingReminderSent(user.getId());
            } catch (Exception e) {
                log.warn("[onboarding-reminder] Ошибка отметки onboarding-reminder userId={}: {}",
                        user.getId(), e.getMessage());
            }
        }

        log.info("[onboarding-reminder] Onboarding-напоминание отправлено: push={}, email={}, всего пользователей={}",
                sentPushes, sentEmails, candidates.size());
    }
}
