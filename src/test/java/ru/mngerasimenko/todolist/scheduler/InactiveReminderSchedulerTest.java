package ru.mngerasimenko.todolist.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.repository.UserRepository;
import ru.mngerasimenko.todolist.service.EmailService;
import ru.mngerasimenko.todolist.service.PushNotificationService;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты для InactiveReminderScheduler.
 * Проверяют логику отправки напоминаний неактивным пользователям.
 */
@ExtendWith(MockitoExtension.class)
class InactiveReminderSchedulerTest {

    @Mock
    private UserRepository userRepository;

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
        // Репозиторий возвращает пустой список
        when(userRepository.findInactiveUsersForReminder(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(Collections.emptyList());

        scheduler.sendReminders();

        // Ни push, ни email не отправлены
        verifyNoInteractions(pushNotificationService);
        verifyNoInteractions(emailService);
    }

    @Test
    void sendReminders_SendsPushAndEmail_WhenUserIsVerified() {
        // Пользователь с подтверждённым email
        when(userRepository.findInactiveUsersForReminder(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(verifiedUser));

        scheduler.sendReminders();

        // Push и email отправлены
        verify(pushNotificationService).sendInactiveReminderPush(1L, "Иван");
        verify(emailService).sendInactiveReminderEmail("ivan@mail.ru", "Иван");
    }

    @Test
    void sendReminders_SendsOnlyPush_WhenEmailNotVerified() {
        // Пользователь без подтверждённого email
        when(userRepository.findInactiveUsersForReminder(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(unverifiedUser));

        scheduler.sendReminders();

        // Push отправлен, email — нет
        verify(pushNotificationService).sendInactiveReminderPush(2L, "Пётр");
        verify(emailService, never()).sendInactiveReminderEmail(anyString(), anyString());
    }

    @Test
    void sendReminders_SetsLastReminderSentAt() {
        // Проверяем что после отправки устанавливается lastReminderSentAt
        assertThat(verifiedUser.getLastReminderSentAt()).isNull();

        when(userRepository.findInactiveUsersForReminder(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(verifiedUser));

        scheduler.sendReminders();

        assertThat(verifiedUser.getLastReminderSentAt()).isNotNull();
        assertThat(verifiedUser.getLastReminderSentAt()).isBeforeOrEqualTo(LocalDateTime.now());
    }

    @Test
    void sendReminders_ContinuesOnPushFailure() {
        // Push бросает исключение — email всё равно должен быть отправлен
        when(userRepository.findInactiveUsersForReminder(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(verifiedUser));
        doThrow(new RuntimeException("Firebase unavailable"))
                .when(pushNotificationService).sendInactiveReminderPush(anyLong(), anyString());

        scheduler.sendReminders();

        // Email всё равно отправлен, несмотря на ошибку push
        verify(emailService).sendInactiveReminderEmail("ivan@mail.ru", "Иван");
        // lastReminderSentAt установлен
        assertThat(verifiedUser.getLastReminderSentAt()).isNotNull();
    }

    @Test
    void sendReminders_ContinuesOnEmailFailure() {
        // Email бросает исключение — обработка следующего пользователя не прерывается
        User secondUser = new User();
        secondUser.setId(3L);
        secondUser.setName("Анна");
        secondUser.setEmail("anna@mail.ru");
        secondUser.setEmailVerified(true);

        when(userRepository.findInactiveUsersForReminder(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(verifiedUser, secondUser));

        // Email для первого пользователя падает, для второго — нет
        doThrow(new RuntimeException("SMTP error"))
                .when(emailService).sendInactiveReminderEmail(eq("ivan@mail.ru"), anyString());

        scheduler.sendReminders();

        // Push отправлен обоим
        verify(pushNotificationService).sendInactiveReminderPush(1L, "Иван");
        verify(pushNotificationService).sendInactiveReminderPush(3L, "Анна");

        // Email попытка для обоих
        verify(emailService).sendInactiveReminderEmail("ivan@mail.ru", "Иван");
        verify(emailService).sendInactiveReminderEmail("anna@mail.ru", "Анна");

        // lastReminderSentAt установлен для обоих
        assertThat(verifiedUser.getLastReminderSentAt()).isNotNull();
        assertThat(secondUser.getLastReminderSentAt()).isNotNull();
    }
}
