package ru.mngerasimenko.todolist.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.mngerasimenko.todolist.featureflags.FeatureFlag;
import ru.mngerasimenko.todolist.featureflags.FeatureFlagStore;
import ru.mngerasimenko.todolist.model.PushToken;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.repository.PushTokenRepository;
import ru.mngerasimenko.todolist.repository.TaskListRepository;
import ru.mngerasimenko.todolist.repository.UserRepository;

import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Mock
    private FeatureFlagStore flagStore;

    @InjectMocks
    private PushNotificationServiceImpl pushNotificationService;

    @BeforeEach
    void setUp() {
        // По умолчанию push включён — существующие сценарии продолжают работать.
        // lenient() — потому что часть тестов (registerToken*) не дёргает flagStore,
        // и Mockito strict mode иначе фейлится с UnnecessaryStubbingException.
        lenient().when(flagStore.isEnabled(FeatureFlag.PUSH_NOTIFICATIONS)).thenReturn(true);
    }

    @Test
    void sendInactiveReminderPush_NoTokens_DoesNotSend() {
        // У пользователя нет push-токенов — метод должен завершиться без ошибок
        when(pushTokenRepository.findFcmTokensByUserId(1L)).thenReturn(Collections.emptyList());

        pushNotificationService.sendInactiveReminderPush(1L, "Иван");

        verify(pushTokenRepository).findFcmTokensByUserId(1L);
        // Firebase не вызывается, исключений нет
        verifyNoMoreInteractions(pushTokenRepository);
    }

    // === registerToken — locale handling ===

    @Test
    void registerToken_NewDevice_NullLocale_FallsBackToRu() {
        // Старый Android-клиент, не шлёт locale — fallback "ru"
        User user = new User();
        user.setId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(pushTokenRepository.findByDeviceId("device-1")).thenReturn(Optional.empty());

        pushNotificationService.registerToken(1L, "fcm-token-abc", "device-1", null);

        ArgumentCaptor<PushToken> captor = ArgumentCaptor.forClass(PushToken.class);
        verify(pushTokenRepository).save(captor.capture());
        assertThat(captor.getValue().getLocale()).isEqualTo("ru");
    }

    @Test
    void registerToken_NewDevice_BlankLocale_FallsBackToRu() {
        // Защита от пустой строки — тоже fallback на "ru"
        User user = new User();
        user.setId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(pushTokenRepository.findByDeviceId("device-2")).thenReturn(Optional.empty());

        pushNotificationService.registerToken(1L, "fcm-token-abc", "device-2", "  ");

        ArgumentCaptor<PushToken> captor = ArgumentCaptor.forClass(PushToken.class);
        verify(pushTokenRepository).save(captor.capture());
        assertThat(captor.getValue().getLocale()).isEqualTo("ru");
    }

    @Test
    void registerToken_NewDevice_ExplicitLocale_PersistsLocale() {
        // Клиент явно прислал "en" — должно сохраниться как "en"
        User user = new User();
        user.setId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(pushTokenRepository.findByDeviceId("device-en")).thenReturn(Optional.empty());

        pushNotificationService.registerToken(1L, "fcm-token-abc", "device-en", "en");

        ArgumentCaptor<PushToken> captor = ArgumentCaptor.forClass(PushToken.class);
        verify(pushTokenRepository).save(captor.capture());
        assertThat(captor.getValue().getLocale()).isEqualTo("en");
    }

    @Test
    void registerToken_ExistingDevice_UpdatesLocale() {
        // Существующий токен (юзер сменил язык приложения) — обновляем locale
        User user = new User();
        user.setId(1L);
        PushToken existing = new PushToken(user, "old-fcm", "device-1", "ru");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(pushTokenRepository.findByDeviceId("device-1")).thenReturn(Optional.of(existing));

        pushNotificationService.registerToken(1L, "new-fcm", "device-1", "en");

        verify(pushTokenRepository).save(existing);
        assertThat(existing.getLocale()).isEqualTo("en");
        assertThat(existing.getFcmToken()).isEqualTo("new-fcm");
    }
}
