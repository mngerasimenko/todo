package ru.mngerasimenko.todolist.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.mngerasimenko.todolist.repository.PushTokenRepository;
import ru.mngerasimenko.todolist.repository.TaskListRepository;
import ru.mngerasimenko.todolist.repository.UserRepository;

import java.util.Collections;

import static org.mockito.Mockito.*;

/**
 * Unit-тесты для PushNotificationServiceImpl.
 * Тестирует только сценарии без обращения к Firebase (статический FirebaseMessaging).
 */
@ExtendWith(MockitoExtension.class)
class PushNotificationServiceImplTest {

    @Mock
    private PushTokenRepository pushTokenRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TaskListRepository taskListRepository;

    @InjectMocks
    private PushNotificationServiceImpl pushNotificationService;

    @Test
    void sendInactiveReminderPush_NoTokens_DoesNotSend() {
        // У пользователя нет push-токенов — метод должен завершиться без ошибок
        when(pushTokenRepository.findFcmTokensByUserId(1L)).thenReturn(Collections.emptyList());

        pushNotificationService.sendInactiveReminderPush(1L, "Иван");

        verify(pushTokenRepository).findFcmTokensByUserId(1L);
        // Firebase не вызывается, исключений нет
        verifyNoMoreInteractions(pushTokenRepository);
    }
}
