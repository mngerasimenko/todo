package ru.mngerasimenko.todolist.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.dto.admin.InactiveReminderTriggerResponse;
import ru.mngerasimenko.todolist.exception.UserNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminServiceImplTest {

    @Mock
    private UserService userService;

    @Mock
    private EmailService emailService;

    @Mock
    private PushNotificationService pushNotificationService;

    @InjectMocks
    private AdminServiceImpl adminService;

    private UserDto user;

    @BeforeEach
    void setUp() {
        user = new UserDto();
        user.setId(42L);
        user.setEmail("target@mail.ru");
        user.setName("Target");
        user.setEmailVerified(true);
    }

    @Test
    void triggerInactiveReminder_userNotFound_ThrowsException() {
        when(userService.getUserByEmail("nobody@mail.ru")).thenReturn(null);

        assertThatThrownBy(() -> adminService.triggerInactiveReminder("nobody@mail.ru"))
                .isInstanceOf(UserNotFoundException.class);

        verifyNoInteractions(emailService, pushNotificationService);
        verify(userService, never()).markReminderSent(anyLong());
    }

    @Test
    void triggerInactiveReminder_verifiedUser_SendsBothChannelsAndMarks() {
        when(userService.getUserByEmail("target@mail.ru")).thenReturn(user);

        InactiveReminderTriggerResponse response = adminService.triggerInactiveReminder("target@mail.ru");

        assertThat(response.getUserId()).isEqualTo(42L);
        assertThat(response.isPushSent()).isTrue();
        assertThat(response.isEmailSent()).isTrue();

        verify(pushNotificationService).sendInactiveReminderPush(42L, "Target");
        verify(emailService).sendInactiveReminderEmail("target@mail.ru", "Target", 42L);
        verify(userService).markReminderSent(42L);
    }

    @Test
    void triggerInactiveReminder_unverifiedEmail_SkipsEmail() {
        user.setEmailVerified(false);
        when(userService.getUserByEmail("target@mail.ru")).thenReturn(user);

        InactiveReminderTriggerResponse response = adminService.triggerInactiveReminder("target@mail.ru");

        assertThat(response.isPushSent()).isTrue();
        assertThat(response.isEmailSent()).isFalse();

        verify(pushNotificationService).sendInactiveReminderPush(42L, "Target");
        verifyNoInteractions(emailService);
        verify(userService).markReminderSent(42L);
    }

    @Test
    void triggerInactiveReminder_pushFails_StillReturnsEmailSentAndMarks() {
        when(userService.getUserByEmail("target@mail.ru")).thenReturn(user);
        doThrow(new RuntimeException("fcm down"))
                .when(pushNotificationService).sendInactiveReminderPush(42L, "Target");

        InactiveReminderTriggerResponse response = adminService.triggerInactiveReminder("target@mail.ru");

        assertThat(response.isPushSent()).isFalse();
        assertThat(response.isEmailSent()).isTrue();

        verify(emailService).sendInactiveReminderEmail("target@mail.ru", "Target", 42L);
        verify(userService).markReminderSent(42L);
    }

    @Test
    void triggerInactiveReminder_emailFails_StillReturnsPushSentAndMarks() {
        when(userService.getUserByEmail("target@mail.ru")).thenReturn(user);
        doThrow(new RuntimeException("smtp down"))
                .when(emailService).sendInactiveReminderEmail("target@mail.ru", "Target", 42L);

        InactiveReminderTriggerResponse response = adminService.triggerInactiveReminder("target@mail.ru");

        assertThat(response.isPushSent()).isTrue();
        assertThat(response.isEmailSent()).isFalse();

        verify(pushNotificationService).sendInactiveReminderPush(42L, "Target");
        verify(userService).markReminderSent(42L);
    }
}
