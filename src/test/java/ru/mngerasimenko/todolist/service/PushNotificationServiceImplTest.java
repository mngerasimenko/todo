package ru.mngerasimenko.todolist.service;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.mngerasimenko.todolist.featureflags.FeatureFlag;
import ru.mngerasimenko.todolist.featureflags.FeatureFlagStore;
import ru.mngerasimenko.todolist.model.PushToken;
import ru.mngerasimenko.todolist.model.TaskList;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.repository.PushTokenRepository;
import ru.mngerasimenko.todolist.repository.TaskListRepository;
import ru.mngerasimenko.todolist.repository.UserRepository;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    @Mock
    private MessageService messageService;

    @Mock
    private FirebaseMessaging firebaseMessaging;

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
        when(pushTokenRepository.findByUserId(1L)).thenReturn(Collections.emptyList());

        pushNotificationService.sendInactiveReminderPush(1L, "Иван");

        verify(pushTokenRepository).findByUserId(1L);
        // Firebase не вызывается, исключений нет
        verifyNoMoreInteractions(pushTokenRepository);
    }

    // === sendTodoDuePush ===

    @Test
    void sendTodoDuePush_CarriesTypeAndDeepLinkIds() throws Exception {
        when(pushTokenRepository.findByUserId(53L)).thenReturn(List.of(tokenFor(53L, "ru")));
        TaskList list = new TaskList();
        list.setId(86L);
        list.setName("Теплица");
        when(taskListRepository.findById(86L)).thenReturn(Optional.of(list));

        try (MockedStatic<FirebaseMessaging> mockedFirebaseMessaging = mockStatic(FirebaseMessaging.class)) {
            mockedFirebaseMessaging.when(FirebaseMessaging::getInstance).thenReturn(firebaseMessaging);

            pushNotificationService.sendTodoDuePush(53L, 777L, 86L, "Полить теплицу");

            ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
            verify(firebaseMessaging).send(captor.capture());
            Map<String, String> data = extractData(captor.getValue());
            // list_id/list_name — те же wire-ключи, что у остальных пяти push-типов;
            // Android-клиент читает именно их для deep link (push_list_id — не wire-ключ).
            assertThat(data).containsEntry("push_type", "todo_due")
                            .containsEntry("todo_id", "777")
                            .containsEntry("list_id", "86")
                            .containsEntry("list_name", "Теплица");
        }
    }

    /** Строит push-токен для userId с заданной локалью — минимальная фикстура для FCM-тестов. */
    private PushToken tokenFor(Long userId, String locale) {
        User user = new User();
        user.setId(userId);
        return new PushToken(user, "fcm-token-" + userId, "device-" + userId, locale);
    }

    /**
     * Достаёт data-payload из собранного FCM Message для проверки в тестах.
     * {@code Message.getData()} package-private в firebase-admin SDK — приходится через reflection.
     */
    private Map<String, String> extractData(Message message) {
        try {
            java.lang.reflect.Field field = Message.class.getDeclaredField("data");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, String> data = (Map<String, String>) field.get(message);
            return data;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // === registerToken — locale handling ===

    @Test
    void registerToken_NewDevice_NullLocale_FallsBackToRu() {
        // Старый Android-клиент, не шлёт locale — fallback "ru"
        pushNotificationService.registerToken(1L, "fcm-token-abc", "device-1", null);

        verify(pushTokenRepository).upsertByDeviceId(1L, "fcm-token-abc", "device-1", "ru");
    }

    @Test
    void registerToken_NewDevice_BlankLocale_FallsBackToRu() {
        // Защита от пустой строки — тоже fallback на "ru"
        pushNotificationService.registerToken(1L, "fcm-token-abc", "device-2", "  ");

        verify(pushTokenRepository).upsertByDeviceId(1L, "fcm-token-abc", "device-2", "ru");
    }

    @Test
    void registerToken_NewDevice_ExplicitLocale_PersistsLocale() {
        // Клиент явно прислал "en" — должно сохраниться как "en"
        pushNotificationService.registerToken(1L, "fcm-token-abc", "device-en", "en");

        verify(pushTokenRepository).upsertByDeviceId(1L, "fcm-token-abc", "device-en", "en");
    }

    @Test
    void registerToken_ExistingDevice_UpdatesViaUpsert() {
        // Существующий токен (юзер сменил язык приложения) — upsert ON CONFLICT
        // обновит существующую строку в БД, локально это просто доп. вызов repo.
        pushNotificationService.registerToken(1L, "new-fcm", "device-1", "en");

        verify(pushTokenRepository).upsertByDeviceId(1L, "new-fcm", "device-1", "en");
        // save больше не вызывается — старый паттерн find+save заменён на native upsert
        verify(pushTokenRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void registerToken_FkViolation_TranslatesToUserNotFound() {
        // Когда user_id не существует, native upsert падает с DataIntegrityViolationException
        // (FK violation). Сервис переводит её в UserNotFoundException — единый exception type
        // с /me/* эндпоинтами, обрабатывается GlobalExceptionHandler как 404.
        doThrow(new org.springframework.dao.DataIntegrityViolationException("FK violation"))
                .when(pushTokenRepository).upsertByDeviceId(eq(99L), any(), any(), any());

        assertThatThrownBy(() ->
                pushNotificationService.registerToken(99L, "fcm", "device-x", "ru")
        ).isInstanceOf(ru.mngerasimenko.todolist.exception.UserNotFoundException.class)
         .hasMessageContaining("User not found: 99");
    }
}
