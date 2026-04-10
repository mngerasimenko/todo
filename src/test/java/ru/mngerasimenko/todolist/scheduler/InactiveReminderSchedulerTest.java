package ru.mngerasimenko.todolist.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

    @InjectMocks
    private InactiveReminderScheduler scheduler;

    private User verifiedUser;
    private User unverifiedUser;

    @BeforeEach
    void setUp() {
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
        when(userService.findInactiveUsersForReminder(3)).thenReturn(Collections.emptyList());

        scheduler.sendReminders();

        // Ни push, ни email не отправлены
        verifyNoInteractions(pushNotificationService);
        verifyNoInteractions(emailService);
        verify(userService, never()).markReminderSent(anyLong());
    }

    @Test
    void sendReminders_SendsPushAndEmail_WhenUserIsVerified() {
        when(userService.findInactiveUsersForReminder(3)).thenReturn(List.of(verifiedUser));

        scheduler.sendReminders();

        verify(pushNotificationService).sendInactiveReminderPush(1L, "Иван");
        verify(emailService).sendInactiveReminderEmail("ivan@mail.ru", "Иван");
        verify(userService).markReminderSent(1L);
    }

    @Test
    void sendReminders_SendsOnlyPush_WhenEmailNotVerified() {
        when(userService.findInactiveUsersForReminder(3)).thenReturn(List.of(unverifiedUser));

        scheduler.sendReminders();

        verify(pushNotificationService).sendInactiveReminderPush(2L, "Пётр");
        verify(emailService, never()).sendInactiveReminderEmail(anyString(), anyString());
        verify(userService).markReminderSent(2L);
    }

    @Test
    void sendReminders_ContinuesOnPushFailure() {
        // Push бросает исключение — email всё равно должен быть отправлен
        when(userService.findInactiveUsersForReminder(3)).thenReturn(List.of(verifiedUser));
        doThrow(new RuntimeException("Firebase unavailable"))
                .when(pushNotificationService).sendInactiveReminderPush(anyLong(), anyString());

        scheduler.sendReminders();

        verify(emailService).sendInactiveReminderEmail("ivan@mail.ru", "Иван");
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

        when(userService.findInactiveUsersForReminder(3)).thenReturn(List.of(verifiedUser, secondUser));
        doThrow(new RuntimeException("SMTP error"))
                .when(emailService).sendInactiveReminderEmail(eq("ivan@mail.ru"), anyString());

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
        when(userService.findInactiveUsersForReminder(3)).thenReturn(List.of(verifiedUser, unverifiedUser));
        doThrow(new RuntimeException("DB error")).when(userService).markReminderSent(1L);

        scheduler.sendReminders();

        verify(pushNotificationService).sendInactiveReminderPush(1L, "Иван");
        verify(pushNotificationService).sendInactiveReminderPush(2L, "Пётр");
        verify(userService).markReminderSent(2L);
    }
}
