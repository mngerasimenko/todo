package ru.mngerasimenko.todolist.service;

import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.SendResponse;
import com.google.firebase.messaging.FirebaseMessagingException;
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

            pushNotificationService.sendTodoDuePush(53L, 777L, 86L, "Полить теплицу", "25.08.2026 09:00");

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

    /**
     * Tag уведомления — контракт с Android-клиентом, а не косметика.
     *
     * При закрытом приложении onMessageReceived не вызывается: уведомление рисует FCM SDK
     * через notify(tag, 0, ...). Без нашего tag он подставляет свой, уникальный на каждое
     * сообщение, и тогда клиент не может ни заменить напоминание по той же задаче, ни снять
     * его — оба дефекта, ради которых это и делалось. Формат обязан посимвольно совпадать
     * с ReminderNotifications.tagFor в todolist-android.
     */
    @Test
    void sendTodoDuePush_SetsDeterministicNotificationTag() throws Exception {
        when(pushTokenRepository.findByUserId(53L)).thenReturn(List.of(tokenFor(53L, "ru")));
        TaskList list = new TaskList();
        list.setId(86L);
        list.setName("Теплица");
        when(taskListRepository.findById(86L)).thenReturn(Optional.of(list));

        try (MockedStatic<FirebaseMessaging> mockedFirebaseMessaging = mockStatic(FirebaseMessaging.class)) {
            mockedFirebaseMessaging.when(FirebaseMessaging::getInstance).thenReturn(firebaseMessaging);

            pushNotificationService.sendTodoDuePush(53L, 777L, 86L, "Полить теплицу", "25.08.2026 09:00");

            ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
            verify(firebaseMessaging).send(captor.capture());
            assertNotificationConfigured(captor.getValue());
            assertThat(extractNotificationTag(captor.getValue())).isEqualTo("todo_due_777");
        }
    }

    /** Прочие типы push тегом не помечаются — им замена по задаче не нужна. */
    @Test
    void notifyNewTodo_HasNoNotificationTag() throws Exception {
        when(pushTokenRepository.findByListIdExcludingUser(86L, 53L))
                .thenReturn(List.of(tokenFor(53L, "ru")));
        TaskList list = new TaskList();
        list.setId(86L);
        list.setName("Теплица");
        when(taskListRepository.findById(86L)).thenReturn(Optional.of(list));

        try (MockedStatic<FirebaseMessaging> mockedFirebaseMessaging = mockStatic(FirebaseMessaging.class)) {
            mockedFirebaseMessaging.when(FirebaseMessaging::getInstance).thenReturn(firebaseMessaging);

            pushNotificationService.notifyNewTodo(86L, 53L, "Иван", "Хлеб");

            ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
            verify(firebaseMessaging).send(captor.capture());
            // Сначала убеждаемся, что AndroidNotification вообще собран и канал тот самый:
            // иначе «tag == null» проходил бы и при отвалившемся AndroidConfig целиком,
            // а вместе с ним отвалились бы channelId, title и body.
            assertNotificationConfigured(captor.getValue());
            assertThat(extractNotificationTag(captor.getValue())).isNull();
        }
    }

    /**
     * Проверяет, что AndroidNotification вообще собран и канал тот, который заводит клиент
     * (`TodoApp.createNotificationChannel`). Без канала система роняет уведомление в
     * fallback-канал FCM SDK — этот дефект в проекте уже был.
     */
    private void assertNotificationConfigured(Message message) {
        assertThat(readField(readField(message, "androidConfig"), "notification")).isNotNull();
        assertThat((String) readField(
                readField(readField(message, "androidConfig"), "notification"), "channelId"))
                .isEqualTo("todo_notifications_v2");
    }

    /** Читает приватное поле по имени — общий кирпич для проверок собранного Message. */
    private Object readField(Object target, String name) {
        if (target == null) {
            return null;
        }
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Не удалось прочитать поле " + name, e);
        }
    }

    /**
     * Достаёт tag из AndroidNotification собранного Message — через reflection, потому что
     * у AndroidConfig и AndroidNotification публичных читателей нет вовсе, только поля
     * (в отличие от Message.getData(), который package-private).
     *
     * Возвращает null и когда tag не задан, и когда отсутствует сам AndroidNotification —
     * поэтому проверять только его недостаточно, см. assertNotificationConfigured.
     */
    private String extractNotificationTag(Message message) {
        try {
            java.lang.reflect.Field androidField = Message.class.getDeclaredField("androidConfig");
            androidField.setAccessible(true);
            Object androidConfig = androidField.get(message);
            if (androidConfig == null) {
                return null;
            }
            java.lang.reflect.Field notificationField =
                    androidConfig.getClass().getDeclaredField("notification");
            notificationField.setAccessible(true);
            Object notification = notificationField.get(androidConfig);
            if (notification == null) {
                return null;
            }
            java.lang.reflect.Field tagField = notification.getClass().getDeclaredField("tag");
            tagField.setAccessible(true);
            return (String) tagField.get(notification);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Не удалось прочитать tag из Message", e);
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

    /**
     * Главный инвариант тихой синхронизации: в сообщении НЕТ notification-payload.
     *
     * Стоит его добавить — и каждая правка чужой задачи начнёт звенеть у всех участников
     * списка. Охранять это одним комментарием в коде мало.
     */
    @Test
    void notifyTodoUpdated_SendsSilentDataOnlyMessage() throws Exception {
        when(flagStore.isEnabled(FeatureFlag.TODO_UPDATE_SYNC_PUSH)).thenReturn(true);
        // Среди получателей — устройство САМОГО редактора (userId 53): у видимых уведомлений
        // его исключают, у тихой синхронизации исключать некого, иначе второе устройство
        // редактора осталось бы со строкой «до правки».
        when(pushTokenRepository.findByListId(86L))
                .thenReturn(List.of(tokenFor(11L, "ru"), tokenFor(53L, "ru")));

        try (MockedStatic<FirebaseMessaging> mockedFirebaseMessaging = mockStatic(FirebaseMessaging.class)) {
            mockedFirebaseMessaging.when(FirebaseMessaging::getInstance).thenReturn(firebaseMessaging);
            when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class)))
                    .thenReturn(mock(BatchResponse.class));

            pushNotificationService.notifyTodoUpdated(86L, 53L, 777L);

            ArgumentCaptor<MulticastMessage> captor = ArgumentCaptor.forClass(MulticastMessage.class);
            verify(firebaseMessaging).sendEachForMulticast(captor.capture());
            MulticastMessage sent = captor.getValue();

            @SuppressWarnings("unchecked")
            List<String> sentTokens = (List<String>) readField(sent, "tokens");
            assertThat(sentTokens).contains("fcm-token-53");

            assertThat(readField(sent, "notification")).isNull();
            Object androidConfig = readField(sent, "androidConfig");
            assertThat(androidConfig).isNotNull();
            assertThat(readField(androidConfig, "notification")).isNull();
            // Wire-ключи — единственное, на чём держится эффект правки: Android читает именно
            // list_id и по нему перечитывает список. Переименуй ключ — фича молча мертва.
            @SuppressWarnings("unchecked")
            Map<String, String> data = (Map<String, String>) readField(sent, "data");
            assertThat(data).containsEntry("push_type", "todo_updated")
                            .containsEntry("list_id", "86");
            // todo_id намеренно НЕ шлём: потребителя у него нет. Клиент читает это поле
            // только при push_type=todo_due, так что здесь оно было бы мёртвым байтом.
            assertThat(data).doesNotContainKey("todo_id");
            // Приоритет задан ЯВНО и он normal: high разбудил бы устройство в Doze, но частые
            // high-priority без взаимодействия — повод для FCM понизить приоритет всему
            // приложению, включая напоминания о сроке. Проверка заодно ловит удаление
            // setPriority: без него поле остаётся null и hasToString падает.
            assertThat(readField(androidConfig, "priority")).hasToString("normal");
            // Ключ схлопывания и TTL защищают ОЧЕРЕДЬ устройства: без них тихая синхронизация,
            // самый частый наш канал, вытесняла бы из неё настоящие уведомления — включая
            // напоминание о сроке.
            assertThat(readField(androidConfig, "collapseKey")).isEqualTo("todo_sync");
            assertThat(readField(androidConfig, "ttl")).isEqualTo("3600s");
        }
    }

    /** Выключенный флаг — ни одного обращения к FCM. */
    @Test
    void notifyTodoUpdated_FlagDisabled_SendsNothing() {
        when(flagStore.isEnabled(FeatureFlag.TODO_UPDATE_SYNC_PUSH)).thenReturn(false);
        pushNotificationService.notifyTodoUpdated(86L, 53L, 777L);

        // Проверяем РЕПОЗИТОРИЙ, а не мок FirebaseMessaging: у сервиса нет поля с ним, SDK
        // достаётся статикой, и без mockStatic мок с продовым кодом не связан вовсе — такой
        // verifyNoInteractions не способен упасть ни при каком поведении. А вот получателей
        // метод спрашивает СРАЗУ после проверки флага, поэтому инвертированная проверка
        // немедленно валит этот тест.
        verify(pushTokenRepository, never()).findByListId(anyLong());
    }

    /**
     * Нарезка по 500 и связка «ответ ↔ токен» ВНУТРИ пачки.
     *
     * `MulticastMessage.build()` сам отбивает больше 500 токенов исключением, поэтому без
     * нарезки большой список не получал бы синхронизацию вовсе. А индексы ответов считаются
     * от пачки, а не от полного списка: ошибка здесь удалила бы ЧУЖОЙ живой push-токен по
     * ответу UNREGISTERED из второй пачки.
     */
    @Test
    void notifyTodoUpdated_SplitsIntoBatchesAndMapsResponsesToTokens() throws Exception {
        when(flagStore.isEnabled(FeatureFlag.TODO_UPDATE_SYNC_PUSH)).thenReturn(true);
        List<PushToken> many = new java.util.ArrayList<>();
        for (long i = 1; i <= 501; i++) {
            many.add(tokenFor(i, "ru"));
        }
        when(pushTokenRepository.findByListId(86L)).thenReturn(many);

        SendResponse failed = mock(SendResponse.class);
        when(failed.isSuccessful()).thenReturn(false);
        FirebaseMessagingException unregistered = mock(FirebaseMessagingException.class);
        when(unregistered.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNREGISTERED);
        when(failed.getException()).thenReturn(unregistered);

        BatchResponse firstBatch = mock(BatchResponse.class);
        when(firstBatch.getResponses()).thenReturn(List.of());
        BatchResponse secondBatch = mock(BatchResponse.class);
        when(secondBatch.getResponses()).thenReturn(List.of(failed));

        try (MockedStatic<FirebaseMessaging> mockedFirebaseMessaging = mockStatic(FirebaseMessaging.class)) {
            mockedFirebaseMessaging.when(FirebaseMessaging::getInstance).thenReturn(firebaseMessaging);
            when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class)))
                    .thenReturn(firstBatch, secondBatch);

            pushNotificationService.notifyTodoUpdated(86L, 53L, 777L);

            ArgumentCaptor<MulticastMessage> captor = ArgumentCaptor.forClass(MulticastMessage.class);
            verify(firebaseMessaging, times(2)).sendEachForMulticast(captor.capture());
            @SuppressWarnings("unchecked")
            List<String> firstTokens = (List<String>) readField(captor.getAllValues().get(0), "tokens");
            @SuppressWarnings("unchecked")
            List<String> secondTokens = (List<String>) readField(captor.getAllValues().get(1), "tokens");
            assertThat(firstTokens).hasSize(500);
            assertThat(secondTokens).containsExactly("fcm-token-501");

            // Неуспешным был единственный ответ ВТОРОЙ пачки — значит спрашивать надо про
            // 501-й токен. Индексация от полного списка дала бы здесь первый.
            verify(pushTokenRepository).findByFcmToken("fcm-token-501");
        }
    }

    /**
     * Транзиентная ошибка FCM НЕ должна удалять живой токен.
     *
     * Без проверки кода сервер за один инцидент (UNAVAILABLE, INTERNAL, QUOTA_EXCEEDED) вычистит
     * токены всех участников, и уведомления вернутся к ним только когда каждое устройство само
     * перерегистрируется. Дефект тихий и необратимый, а канал самый частый — попасть в окно
     * шансов больше, чем у соседей.
     */
    @Test
    void notifyTodoUpdated_TransientFailure_KeepsToken() throws Exception {
        when(flagStore.isEnabled(FeatureFlag.TODO_UPDATE_SYNC_PUSH)).thenReturn(true);
        when(pushTokenRepository.findByListId(86L)).thenReturn(List.of(tokenFor(11L, "ru")));

        SendResponse failed = mock(SendResponse.class);
        when(failed.isSuccessful()).thenReturn(false);
        FirebaseMessagingException transientError = mock(FirebaseMessagingException.class);
        when(transientError.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNAVAILABLE);
        when(failed.getException()).thenReturn(transientError);
        BatchResponse response = mock(BatchResponse.class);
        when(response.getResponses()).thenReturn(List.of(failed));

        try (MockedStatic<FirebaseMessaging> mockedFirebaseMessaging = mockStatic(FirebaseMessaging.class)) {
            mockedFirebaseMessaging.when(FirebaseMessaging::getInstance).thenReturn(firebaseMessaging);
            when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class))).thenReturn(response);

            pushNotificationService.notifyTodoUpdated(86L, 53L, 777L);

            verify(pushTokenRepository, never()).findByFcmToken(any());
        }
    }

    /** Глобальный рубильник гасит и этот канал — им в первую очередь и будут гасить самый частый. */
    @Test
    void notifyTodoUpdated_PushGloballyDisabled_SendsNothing() {
        when(flagStore.isEnabled(FeatureFlag.PUSH_NOTIFICATIONS)).thenReturn(false);
        // Без этой заглушки метод выходил бы на ВТОРОМ гарде (флаг канала по умолчанию false),
        // и тест оставался бы зелёным даже без самого рубильника. lenient — потому что при
        // живом pushDisabled() до неё не доходит.
        lenient().when(flagStore.isEnabled(FeatureFlag.TODO_UPDATE_SYNC_PUSH)).thenReturn(true);

        pushNotificationService.notifyTodoUpdated(86L, 53L, 777L);

        verify(pushTokenRepository, never()).findByListId(anyLong());
    }
}
