package ru.mngerasimenko.todolist.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.mngerasimenko.todolist.featureflags.FeatureFlag;
import ru.mngerasimenko.todolist.featureflags.FeatureFlagStore;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.service.EmailService;
import ru.mngerasimenko.todolist.service.PushNotificationService;
import ru.mngerasimenko.todolist.service.UserService;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты для OnboardingReminderScheduler (Phase 3.3).
 * Проверяют логику отправки 3-дневного onboarding-напоминания.
 */
@ExtendWith(MockitoExtension.class)
class OnboardingReminderSchedulerTest {

    @Mock
    private UserService userService;

    @Mock
    private EmailService emailService;

    @Mock
    private PushNotificationService pushNotificationService;

    @Mock
    private FeatureFlagStore flagStore;

    @InjectMocks
    private OnboardingReminderScheduler scheduler;

    private User verifiedUser;

    @BeforeEach
    void setUp() {
        lenient().when(flagStore.isEnabled(FeatureFlag.ONBOARDING_REMINDER)).thenReturn(true);
        lenient().when(userService.issueUnsubscribeToken(anyLong())).thenReturn("test-token");

        verifiedUser = new User();
        verifiedUser.setId(10L);
        verifiedUser.setName("Мария");
        verifiedUser.setEmail("maria@mail.ru");
        verifiedUser.setEmailVerified(true);
        verifiedUser.setPreferredEmailLocale("ru");
    }

    @Test
    void sendOnboardingReminders_FlagDisabled_DoesNothing() {
        when(flagStore.isEnabled(FeatureFlag.ONBOARDING_REMINDER)).thenReturn(false);

        scheduler.sendOnboardingReminders();

        verifyNoInteractions(userService);
        verifyNoInteractions(pushNotificationService);
        verifyNoInteractions(emailService);
    }

    @Test
    void sendOnboardingReminders_NoCandidates_DoesNothing() {
        when(userService.findOnboardingReminderCandidates(3)).thenReturn(Collections.emptyList());

        scheduler.sendOnboardingReminders();

        verifyNoInteractions(pushNotificationService);
        verifyNoInteractions(emailService);
        verify(userService, never()).markOnboardingReminderSent(anyLong());
    }

    @Test
    void sendOnboardingReminders_HappyPath_SendsPushAndEmailAndMarks() {
        when(userService.findOnboardingReminderCandidates(3)).thenReturn(List.of(verifiedUser));

        scheduler.sendOnboardingReminders();

        verify(pushNotificationService).sendOnboardingReminderPush(10L, "Мария");
        verify(emailService).sendOnboardingReminderEmail(
                eq("maria@mail.ru"), eq("Мария"), eq(10L), eq("ru"), eq("test-token"));
        verify(userService).markOnboardingReminderSent(10L);
    }

    @Test
    void sendOnboardingReminders_EmailNotVerified_SkipsEmailButSendsPush() {
        // Query из findOnboardingReminderCandidates обычно фильтрует emailVerified=true,
        // но scheduler делает defensive-проверку — этот тест её проверяет.
        verifiedUser.setEmailVerified(false);
        when(userService.findOnboardingReminderCandidates(3)).thenReturn(List.of(verifiedUser));

        scheduler.sendOnboardingReminders();

        verify(pushNotificationService).sendOnboardingReminderPush(10L, "Мария");
        verify(emailService, never()).sendOnboardingReminderEmail(
                anyString(), anyString(), anyLong(), anyString(), anyString());
        verify(userService).markOnboardingReminderSent(10L);
    }

    @Test
    void sendOnboardingReminders_ContinuesOnPushFailure() {
        when(userService.findOnboardingReminderCandidates(3)).thenReturn(List.of(verifiedUser));
        doThrow(new RuntimeException("Firebase down"))
                .when(pushNotificationService).sendOnboardingReminderPush(anyLong(), anyString());

        scheduler.sendOnboardingReminders();

        verify(emailService).sendOnboardingReminderEmail(
                eq("maria@mail.ru"), eq("Мария"), eq(10L), eq("ru"), eq("test-token"));
        verify(userService).markOnboardingReminderSent(10L);
    }

    @Test
    void sendOnboardingReminders_ContinuesOnEmailFailure() {
        when(userService.findOnboardingReminderCandidates(3)).thenReturn(List.of(verifiedUser));
        doThrow(new RuntimeException("SMTP down"))
                .when(emailService).sendOnboardingReminderEmail(
                        anyString(), anyString(), anyLong(), anyString(), anyString());

        scheduler.sendOnboardingReminders();

        verify(pushNotificationService).sendOnboardingReminderPush(10L, "Мария");
        // markOnboardingReminderSent всё равно вызван — мы не хотим зацикливаться на одном юзере
        // если email-канал упал
        verify(userService).markOnboardingReminderSent(10L);
    }

    @Test
    void sendOnboardingReminders_SendsEmailWithoutTokenWhenTokenIssueFails() {
        // issueUnsubscribeToken падает — email всё равно идёт, но с null-токеном
        // (footer-link не отрендерится).
        when(userService.findOnboardingReminderCandidates(3)).thenReturn(List.of(verifiedUser));
        when(userService.issueUnsubscribeToken(10L)).thenThrow(new RuntimeException("DB write failed"));

        scheduler.sendOnboardingReminders();

        verify(emailService).sendOnboardingReminderEmail(
                eq("maria@mail.ru"), eq("Мария"), eq(10L), eq("ru"), isNull());
        verify(userService).markOnboardingReminderSent(10L);
    }
}
