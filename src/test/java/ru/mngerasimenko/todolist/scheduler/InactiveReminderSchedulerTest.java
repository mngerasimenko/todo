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
 * Unit-тесты для InactiveReminderScheduler.
 * Проверяют логику отправки напоминаний неактивным пользователям.
 */
@ExtendWith(MockitoExtension.class)
class InactiveReminderSchedulerTest {

    @Mock
    private UserService userService;

    @Mock
    private EmailService emailService;

    @Mock
    private PushNotificationService pushNotificationService;

    @Mock
    private FeatureFlagStore flagStore;

    @InjectMocks
    private InactiveReminderScheduler scheduler;

    private User verifiedUser;
    private User unverifiedUser;

    @BeforeEach
    void setUp() {
        // Флаг включён — существующие сценарии пропускают через дефолтное поведение
        lenient().when(flagStore.isEnabled(FeatureFlag.INACTIVE_REMINDER)).thenReturn(true);
        // Token issuance stub: scheduler issues a fresh unsubscribe-token before email
        lenient().when(userService.issueUnsubscribeToken(anyLong())).thenReturn("test-token");

        verifiedUser = new User();
        verifiedUser.setId(1L);
        verifiedUser.setName("Иван");
        verifiedUser.setEmail("ivan@mail.ru");
        verifiedUser.setEmailVerified(true);

        unverifiedUser = new User();
        unverifiedUser.setId(2L);
        unverifiedUser.setName("Пётр");
        unverifiedUser.setEmail("petr@mail.ru");
        unverifiedUser.setEmailVerified(false);
    }

    @Test
    void sendReminders_NoInactiveUsers_DoesNothing() {
        // Сервис возвращает пустой список
        when(userService.findInactiveUsersForReminder(7)).thenReturn(Collections.emptyList());

        scheduler.sendReminders();

        // Ни push, ни email не отправлены
        verifyNoInteractions(pushNotificationService);
        verifyNoInteractions(emailService);
        verify(userService, never()).markReminderSent(anyLong());
    }

    @Test
    void sendReminders_SendsPushAndEmail_WhenUserIsVerified() {
        when(userService.findInactiveUsersForReminder(7)).thenReturn(List.of(verifiedUser));

        scheduler.sendReminders();

        verify(pushNotificationService).sendInactiveReminderPush(1L, "Иван");
        verify(emailService).sendInactiveReminderEmail(eq("ivan@mail.ru"), eq("Иван"), eq(1L), anyString(), eq("test-token"));
        verify(userService).markReminderSent(1L);
    }

    @Test
    void sendReminders_SendsOnlyPush_WhenEmailNotVerified() {
        when(userService.findInactiveUsersForReminder(7)).thenReturn(List.of(unverifiedUser));

        scheduler.sendReminders();

        verify(pushNotificationService).sendInactiveReminderPush(2L, "Пётр");
        verify(emailService, never()).sendInactiveReminderEmail(anyString(), anyString(), anyLong(), anyString(), anyString());
        verify(userService).markReminderSent(2L);
    }

    @Test
    void sendReminders_ContinuesOnPushFailure() {
        // Push бросает исключение — email всё равно должен быть отправлен
        when(userService.findInactiveUsersForReminder(7)).thenReturn(List.of(verifiedUser));
        doThrow(new RuntimeException("Firebase unavailable"))
                .when(pushNotificationService).sendInactiveReminderPush(anyLong(), anyString());

        scheduler.sendReminders();

        verify(emailService).sendInactiveReminderEmail(eq("ivan@mail.ru"), eq("Иван"), eq(1L), anyString(), eq("test-token"));
        verify(userService).markReminderSent(1L);
    }

    @Test
    void sendReminders_ContinuesOnEmailFailure() {
        // Email бросает исключение — обработка следующего пользователя не прерывается
        User secondUser = new User();
        secondUser.setId(3L);
        secondUser.setName("Анна");
        secondUser.setEmail("anna@mail.ru");
        secondUser.setEmailVerified(true);

        when(userService.findInactiveUsersForReminder(7)).thenReturn(List.of(verifiedUser, secondUser));
        doThrow(new RuntimeException("SMTP error"))
                .when(emailService).sendInactiveReminderEmail(eq("ivan@mail.ru"), anyString(), anyLong(), anyString(), anyString());

        scheduler.sendReminders();

        // Push отправлен обоим
        verify(pushNotificationService).sendInactiveReminderPush(1L, "Иван");
        verify(pushNotificationService).sendInactiveReminderPush(3L, "Анна");
        // markReminderSent вызван для обоих
        verify(userService).markReminderSent(1L);
        verify(userService).markReminderSent(3L);
    }

    @Test
    void sendReminders_ContinuesOnMarkReminderFailure() {
        // markReminderSent падает — следующий пользователь всё равно обрабатывается
        when(userService.findInactiveUsersForReminder(7)).thenReturn(List.of(verifiedUser, unverifiedUser));
        doThrow(new RuntimeException("DB error")).when(userService).markReminderSent(1L);

        scheduler.sendReminders();

        verify(pushNotificationService).sendInactiveReminderPush(1L, "Иван");
        verify(pushNotificationService).sendInactiveReminderPush(2L, "Пётр");
        verify(userService).markReminderSent(2L);
    }

    @Test
    void sendReminders_SendsEmailWithoutTokenWhenTokenIssueFails() {
        // issueUnsubscribeToken падает — email всё равно отправляется, но с null-токеном
        // (footer-link не отрендерится — degraded, не broken).
        when(userService.findInactiveUsersForReminder(7)).thenReturn(List.of(verifiedUser));
        when(userService.issueUnsubscribeToken(1L)).thenThrow(new RuntimeException("DB write failed"));

        scheduler.sendReminders();

        verify(emailService).sendInactiveReminderEmail(
                eq("ivan@mail.ru"), eq("Иван"), eq(1L), anyString(), isNull());
        verify(userService).markReminderSent(1L);
    }
}
