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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import ru.mngerasimenko.todolist.config.I18nConfig;
import ru.mngerasimenko.todolist.featureflags.FeatureFlag;
import ru.mngerasimenko.todolist.featureflags.FeatureFlagStore;
import ru.mngerasimenko.todolist.model.PushToken;
import ru.mngerasimenko.todolist.model.TaskList;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.repository.PushTokenRepository;
import ru.mngerasimenko.todolist.repository.TaskListRepository;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты для PushNotificationServiceImpl.
 * Статический FirebaseMessaging подменяется через mockStatic там, где сценарий доходит до отправки.
 */
@ExtendWith(MockitoExtension.class)
class PushNotificationServiceImplTest {

    @Mock
    private PushTokenRepository pushTokenRepository;

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
        when(taskListRepository.findById(86L)).thenReturn(Optional.of(listNamed(86L, "Теплица")));

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
        when(taskListRepository.findById(86L)).thenReturn(Optional.of(listNamed(86L, "Теплица")));

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
        when(taskListRepository.findById(86L)).thenReturn(Optional.of(listNamed(86L, "Теплица")));

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
     * Сбой чистки мёртвого токена не обрывает рассылку остальным.
     *
     * fcm_token в схеме НЕ уникален (уникален device_id), и дубль роняет findByFcmToken через
     * IncorrectResultSizeDataAccessException. Без защиты исключение вылетало из цикла, и
     * видимое уведомление не получал никто из участников, стоящих в списке после мёртвого.
     */
    @Test
    void notifyNewTodo_DeadTokenCleanupFails_StillSendsToOthers() throws Exception {
        when(pushTokenRepository.findByListIdExcludingUser(86L, 53L))
                .thenReturn(List.of(tokenFor(11L, "ru"), tokenFor(12L, "ru")));
        FirebaseMessagingException unregistered = mock(FirebaseMessagingException.class);
        when(unregistered.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNREGISTERED);
        when(pushTokenRepository.findByFcmToken("fcm-token-11"))
                .thenThrow(new IncorrectResultSizeDataAccessException(1, 2));

        try (MockedStatic<FirebaseMessaging> mockedFirebaseMessaging = mockStatic(FirebaseMessaging.class)) {
            mockedFirebaseMessaging.when(FirebaseMessaging::getInstance).thenReturn(firebaseMessaging);
            when(firebaseMessaging.send(any(Message.class))).thenThrow(unregistered).thenReturn("ok");

            assertThatCode(() -> pushNotificationService.notifyNewTodo(86L, 53L, "Иван", "Хлеб"))
                    .doesNotThrowAnyException();

            ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
            verify(firebaseMessaging, times(2)).send(captor.capture());
            assertThat(sentToken(captor.getAllValues().get(1))).isEqualTo("fcm-token-12");
            // Явная проверка, что чистка шла именно по fcm-token-11, а не держится на побочном
            // эффекте strict stubs.
            verify(pushTokenRepository).findByFcmToken("fcm-token-11");
            verify(pushTokenRepository, never()).delete(any());
        }
    }

    /**
     * Защищён весь блок чистки, а не только поиск: падение самого delete (недоступная БД,
     * гонка с параллельной перерегистрацией токена) тоже не должно обрывать рассылку.
     */
    @Test
    void notifyNewTodo_DeadTokenDeleteFails_StillSendsToOthers() throws Exception {
        when(pushTokenRepository.findByListIdExcludingUser(86L, 53L))
                .thenReturn(List.of(tokenFor(11L, "ru"), tokenFor(12L, "ru")));
        // Отдельный экземпляр, а не получатель из списка: у PushToken нет equals, и так проверка
        // ниже отличает удаление найденной строки от удаления объекта-получателя.
        PushToken dead = tokenFor(11L, "ru");
        when(pushTokenRepository.findByFcmToken("fcm-token-11")).thenReturn(Optional.of(dead));
        doThrow(new ObjectOptimisticLockingFailureException(PushToken.class, 11L))
                .when(pushTokenRepository).delete(dead);
        FirebaseMessagingException unregistered = mock(FirebaseMessagingException.class);
        when(unregistered.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNREGISTERED);

        try (MockedStatic<FirebaseMessaging> mockedFirebaseMessaging = mockStatic(FirebaseMessaging.class)) {
            mockedFirebaseMessaging.when(FirebaseMessaging::getInstance).thenReturn(firebaseMessaging);
            when(firebaseMessaging.send(any(Message.class))).thenThrow(unregistered).thenReturn("ok");

            assertThatCode(() -> pushNotificationService.notifyNewTodo(86L, 53L, "Иван", "Хлеб"))
                    .doesNotThrowAnyException();

            ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
            verify(firebaseMessaging, times(2)).send(captor.capture());
            assertThat(readField(captor.getAllValues().get(1), "token")).isEqualTo("fcm-token-12");
            verify(pushTokenRepository).delete(dead);
        }
    }

    /**
     * Штатная чистка: мёртвый токен удаляется, а рассылка идёт дальше.
     *
     * Удаляется строка, найденная заново по fcm_token, а не сущность из списка получателей:
     * upsert по device_id сохраняет id строки, и удаление по сущности снесло бы уже
     * перерегистрированный живой токен. Поэтому поиск возвращает ОТДЕЛЬНЫЙ экземпляр.
     */
    @Test
    void notifyNewTodo_UnregisteredToken_DeletedAndOthersStillSent() throws Exception {
        when(pushTokenRepository.findByListIdExcludingUser(86L, 53L))
                .thenReturn(List.of(tokenFor(11L, "ru"), tokenFor(12L, "ru")));
        FirebaseMessagingException unregistered = mock(FirebaseMessagingException.class);
        when(unregistered.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNREGISTERED);
        PushToken found = tokenFor(11L, "ru");
        when(pushTokenRepository.findByFcmToken("fcm-token-11")).thenReturn(Optional.of(found));

        try (MockedStatic<FirebaseMessaging> mockedFirebaseMessaging = mockStatic(FirebaseMessaging.class)) {
            mockedFirebaseMessaging.when(FirebaseMessaging::getInstance).thenReturn(firebaseMessaging);
            when(firebaseMessaging.send(any(Message.class))).thenThrow(unregistered).thenReturn("ok");

            pushNotificationService.notifyNewTodo(86L, 53L, "Иван", "Хлеб");

            verify(pushTokenRepository).delete(found);
            verify(pushTokenRepository, times(1)).delete(any());
            verify(firebaseMessaging, times(2)).send(any(Message.class));
        }
    }

    /**
     * Транзиентная ошибка FCM НЕ удаляет живой токен — тот же инвариант, что у
     * notifyTodoUpdated_TransientFailure_KeepsToken, но для поштучной отправки видимых push.
     */
    @Test
    void notifyNewTodo_TransientFailure_KeepsTokenAndSendsToOthers() throws Exception {
        when(pushTokenRepository.findByListIdExcludingUser(86L, 53L))
                .thenReturn(List.of(tokenFor(11L, "ru"), tokenFor(12L, "ru")));
        FirebaseMessagingException transientError = mock(FirebaseMessagingException.class);
        when(transientError.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNAVAILABLE);

        try (MockedStatic<FirebaseMessaging> mockedFirebaseMessaging = mockStatic(FirebaseMessaging.class)) {
            mockedFirebaseMessaging.when(FirebaseMessaging::getInstance).thenReturn(firebaseMessaging);
            when(firebaseMessaging.send(any(Message.class))).thenThrow(transientError).thenReturn("ok");

            pushNotificationService.notifyNewTodo(86L, 53L, "Иван", "Хлеб");

            verify(firebaseMessaging, times(2)).send(any(Message.class));
            verify(pushTokenRepository).findByListIdExcludingUser(86L, 53L);
            verifyNoMoreInteractions(pushTokenRepository);
        }
    }

    /**
     * Неподнятый Firebase не уходит в обработчик необработанных исключений @Async.
     *
     * FirebaseMessaging.getInstance() без инициализированного FirebaseApp бросает
     * IllegalStateException, а узкий catch (FirebaseMessagingException) её пропускал — причина
     * терялась мимо нашего лога. FirebaseConfig поднимает приложение один раз на старте, поэтому
     * getInstance падает на КАЖДОМ токене, а не на одном.
     */
    @Test
    void notifyNewTodo_FirebaseNotInitialized_DoesNotThrowOrTouchTokens() {
        when(pushTokenRepository.findByListIdExcludingUser(86L, 53L))
                .thenReturn(List.of(tokenFor(11L, "ru"), tokenFor(12L, "ru")));

        try (MockedStatic<FirebaseMessaging> mockedFirebaseMessaging = mockStatic(FirebaseMessaging.class)) {
            mockedFirebaseMessaging.when(FirebaseMessaging::getInstance)
                    .thenThrow(new IllegalStateException("FirebaseApp with name [DEFAULT] doesn't exist."));

            assertThatCode(() -> pushNotificationService.notifyNewTodo(86L, 53L, "Иван", "Хлеб"))
                    .doesNotThrowAnyException();

            // Иначе широкий catch мог проглотить сбой, случившийся ещё до обращения к Firebase.
            mockedFirebaseMessaging.verify(FirebaseMessaging::getInstance, atLeastOnce());
            // Сбой отправки — не повод трогать токен ни одним способом: удаляем только по UNREGISTERED.
            verify(pushTokenRepository).findByListIdExcludingUser(86L, 53L);
            verifyNoMoreInteractions(pushTokenRepository);
        }
    }

    /** Непроверяемое исключение на одном токене не обрывает рассылку остальным. */
    @Test
    void notifyNewTodo_RuntimeFailureOnOneToken_StillSendsToOthers() throws Exception {
        when(pushTokenRepository.findByListIdExcludingUser(86L, 53L))
                .thenReturn(List.of(tokenFor(11L, "ru"), tokenFor(12L, "ru")));

        try (MockedStatic<FirebaseMessaging> mockedFirebaseMessaging = mockStatic(FirebaseMessaging.class)) {
            mockedFirebaseMessaging.when(FirebaseMessaging::getInstance).thenReturn(firebaseMessaging);
            when(firebaseMessaging.send(any(Message.class)))
                    .thenThrow(new IllegalArgumentException("broken message"))
                    .thenReturn("ok");

            assertThatCode(() -> pushNotificationService.notifyNewTodo(86L, 53L, "Иван", "Хлеб"))
                    .doesNotThrowAnyException();

            ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
            verify(firebaseMessaging, times(2)).send(captor.capture());
            assertThat(sentToken(captor.getAllValues().get(1))).isEqualTo("fcm-token-12");
            verify(pushTokenRepository).findByListIdExcludingUser(86L, 53L);
            verifyNoMoreInteractions(pushTokenRepository);
        }
    }

    // === push_type: wire-контракт с Android-клиентом ===

    /**
     * push_type и list_id/list_name — то, что читает Android: по list_id он открывает список,
     * а по todo_due — саму задачу. Проверяется СОБРАННОЕ сообщение, а не константа сервиса:
     * ассерт на константу остался бы зелёным и у кода, который забыл положить её в payload.
     */
    @Test
    void notifyNewTodo_CarriesTaskAddedTypeAndListIds() throws Exception {
        when(pushTokenRepository.findByListIdExcludingUser(86L, 53L))
                .thenReturn(List.of(tokenFor(11L, "ru")));
        when(taskListRepository.findById(86L)).thenReturn(Optional.of(listNamed(86L, "Теплица")));

        try (MockedStatic<FirebaseMessaging> mockedFirebaseMessaging = mockStatic(FirebaseMessaging.class)) {
            mockedFirebaseMessaging.when(FirebaseMessaging::getInstance).thenReturn(firebaseMessaging);

            pushNotificationService.notifyNewTodo(86L, 53L, "Иван", "Хлеб");

            assertThat(extractData(sentMessages(1).get(0)))
                    .containsEntry("push_type", "task_added")
                    .containsEntry("list_id", "86")
                    .containsEntry("list_name", "Теплица");
        }
    }

    /** Тексты настоящие: у этой пары ключей title как раз различается по локали. */
    @Test
    void notifyTodoCompleted_CarriesTaskCompletedTypeAndLocalizedText() throws Exception {
        when(pushTokenRepository.findByListIdExcludingUser(86L, 53L))
                .thenReturn(List.of(tokenFor(11L, "ru-RU"), tokenFor(12L, "en-US")));
        when(taskListRepository.findById(86L)).thenReturn(Optional.of(listNamed(86L, "Теплица")));

        try (MockedStatic<FirebaseMessaging> mockedFirebaseMessaging = mockStatic(FirebaseMessaging.class)) {
            mockedFirebaseMessaging.when(FirebaseMessaging::getInstance).thenReturn(firebaseMessaging);

            serviceWithRealMessages().notifyTodoCompleted(53L, 86L, "Иван", "Хлеб");

            List<Message> sent = sentMessages(2);
            assertThat(extractData(sent.get(0)))
                    .containsEntry("push_type", "task_completed")
                    .containsEntry("list_id", "86")
                    .containsEntry("list_name", "Теплица");
            assertThat(sentToken(sent.get(0))).isEqualTo("fcm-token-11");
            assertThat(notificationTitle(sent.get(0))).isEqualTo("Задача выполнена ✓");
            assertThat(notificationBody(sent.get(0))).isEqualTo("Иван: Хлеб");
            assertThat(sentToken(sent.get(1))).isEqualTo("fcm-token-12");
            assertThat(notificationTitle(sent.get(1))).isEqualTo("Task done ✓");
            // Английский body тоже сверяется целиком: перестановка плейсхолдеров в одном бандле
            // («{1}: {0}») дала бы связное «Хлеб: Иван», и ни один ассерт на title её не увидит.
            assertThat(notificationBody(sent.get(1))).isEqualTo("Иван: Хлеб");
        }
    }

    /**
     * {@code list_name} берётся из РЕПОЗИТОРИЯ, а не из аргумента вызова: список могли
     * переименовать после выдачи инвайта, и в уведомлении должно стоять актуальное имя.
     * Поэтому имя в аргументе и имя в базе здесь намеренно разные.
     */
    @Test
    void notifyNewMember_CarriesMemberAddedTypeAndListNameFromRepository() throws Exception {
        when(pushTokenRepository.findByListIdExcludingUser(86L, 53L))
                .thenReturn(List.of(tokenFor(11L, "ru-RU"), tokenFor(12L, "en-US")));
        when(taskListRepository.findById(86L)).thenReturn(Optional.of(listNamed(86L, "Теплица")));

        try (MockedStatic<FirebaseMessaging> mockedFirebaseMessaging = mockStatic(FirebaseMessaging.class)) {
            mockedFirebaseMessaging.when(FirebaseMessaging::getInstance).thenReturn(firebaseMessaging);

            serviceWithRealMessages().notifyNewMember(86L, 53L, "Иван", "Имя на момент инвайта");

            List<Message> sent = sentMessages(2);
            assertThat(extractData(sent.get(0)))
                    .containsEntry("push_type", "member_added")
                    .containsEntry("list_id", "86")
                    .containsEntry("list_name", "Теплица");
            assertThat(sentToken(sent.get(0))).isEqualTo("fcm-token-11");
            assertThat(notificationTitle(sent.get(0))).isEqualTo("Новый участник");
            // А в ТЕКСТ уведомления идёт имя из аргумента — это отдельный источник, и путать
            // их нельзя: ассерт на одно и то же имя не различил бы подмену одного другим.
            assertThat(notificationBody(sent.get(0))).isEqualTo("Иван: Имя на момент инвайта");
            assertThat(sentToken(sent.get(1))).isEqualTo("fcm-token-12");
            assertThat(notificationTitle(sent.get(1))).isEqualTo("New member");
            assertThat(notificationBody(sent.get(1))).isEqualTo("Иван: Имя на момент инвайта");
        }
    }

    // === Локализация текстов по локали КАЖДОГО токена ===

    /**
     * Локаль берётся у каждого токена отдельно, а не у первого: у одного пользователя телефон
     * бывает русским, а планшет английским, и в списке рядом стоят устройства разных участников.
     * <p>
     * Теги здесь такие, какие реально лежат в {@code push_token.locale}: Android шлёт
     * {@code Locale.getDefault().toLanguageTag()}, а {@code LocaleNormalizer} сохраняет регион —
     * то есть {@code en-US}, а не {@code en}. На голых тегах тест был бы слабее: подмена
     * {@code Locale.forLanguageTag} на {@code new Locale(тег)} резолвит {@code "en"} правильно,
     * а {@code "en-US"} — в язык {@code "en-us"}, бандла с таким именем нет, и КАЖДОЕ английское
     * устройство получило бы русский push. Голый {@code ru} тоже нужен: его ставит fallback
     * {@code registerToken} старым клиентам, которые поле locale вообще не шлют.
     * <p>
     * Тексты настоящие, из {@code messages*.properties} — мок MessageService вернул бы null
     * при любом ключе и пропустил бы и опечатку в имени ключа, и потерю локали.
     */
    @Test
    void notifyNewTodo_TokensWithDifferentLocales_RenderTextPerTokenLocale() throws Exception {
        when(pushTokenRepository.findByListIdExcludingUser(86L, 53L))
                .thenReturn(List.of(tokenFor(11L, "ru-RU"), tokenFor(12L, "en-US"), tokenFor(13L, "ru")));
        when(taskListRepository.findById(86L)).thenReturn(Optional.of(listNamed(86L, "Теплица")));

        try (MockedStatic<FirebaseMessaging> mockedFirebaseMessaging = mockStatic(FirebaseMessaging.class)) {
            mockedFirebaseMessaging.when(FirebaseMessaging::getInstance).thenReturn(firebaseMessaging);

            serviceWithRealMessages().notifyNewTodo(86L, 53L, "Иван", "Хлеб");

            // Текст сверяется вместе с ТОКЕНОМ, на который он ушёл: одних title'ов мало —
            // пересборка цикла со сдвигом индекса отправила бы английский текст на русское
            // устройство, а число сообщений, их порядок и набор текстов остались бы теми же.
            List<Message> sent = sentMessages(3);
            assertThat(sentToken(sent.get(0))).isEqualTo("fcm-token-11");
            assertThat(notificationTitle(sent.get(0))).isEqualTo("Новая задача");
            assertThat(sentToken(sent.get(1))).isEqualTo("fcm-token-12");
            assertThat(notificationTitle(sent.get(1))).isEqualTo("New task");
            assertThat(sentToken(sent.get(2))).isEqualTo("fcm-token-13");
            assertThat(notificationTitle(sent.get(2))).isEqualTo("Новая задача");
            // Шаблон body у этого ключа одинаков в обоих бандлах («{0}: {1}»), поэтому здесь
            // проверяется не разный текст, а то, что подстановка аргументов прошла и на
            // английском устройстве — разный текст по локали держат тесты напоминаний ниже.
            assertThat(notificationBody(sent.get(0))).isEqualTo("Иван: Хлеб");
            assertThat(notificationBody(sent.get(1))).isEqualTo("Иван: Хлеб");
        }
    }

    /**
     * Напоминание о сроке: текст рендерится настоящий, и аргументы стоят в своём порядке.
     * <p>
     * Это единственная пара ключей, где перестановка двух аргументов даёт связный, но неверный
     * текст — «25.08.2026 09:00 — до Полить теплицу». Такой свап проходил мимо всего: тесты
     * {@code sendTodoDuePush_*} выше идут с мокнутым MessageService (title/body там null), а
     * {@code TodoReminderSchedulerTest} держит оба строковых аргумента под {@code any()}.
     */
    @Test
    void sendTodoDuePush_RendersLocalizedTextWithArgumentsInOrder() throws Exception {
        when(pushTokenRepository.findByUserId(53L))
                .thenReturn(List.of(tokenFor(11L, "ru-RU"), tokenFor(12L, "en-US")));
        when(taskListRepository.findById(86L)).thenReturn(Optional.of(listNamed(86L, "Теплица")));

        try (MockedStatic<FirebaseMessaging> mockedFirebaseMessaging = mockStatic(FirebaseMessaging.class)) {
            mockedFirebaseMessaging.when(FirebaseMessaging::getInstance).thenReturn(firebaseMessaging);

            serviceWithRealMessages()
                    .sendTodoDuePush(53L, 777L, 86L, "Полить теплицу", "25.08.2026 09:00");

            List<Message> sent = sentMessages(2);
            assertThat(sentToken(sent.get(0))).isEqualTo("fcm-token-11");
            assertThat(notificationTitle(sent.get(0))).isEqualTo("Напоминание о задаче");
            assertThat(sentToken(sent.get(1))).isEqualTo("fcm-token-12");
            assertThat(notificationTitle(sent.get(1))).isEqualTo("Task reminder");
            // Сравнение по краям, а не целиком: тире в шаблоне — типографское, и точный литерал
            // ломался бы от правки пунктуации, ничего при этом не охраняя. Порядок аргументов
            // такая проверка держит: при свапе body начинается с даты, а не с названия задачи.
            assertThat(notificationBody(sent.get(0)))
                    .startsWith("Полить теплицу")
                    .endsWith("25.08.2026 09:00")
                    .contains("до");
            assertThat(notificationBody(sent.get(1)))
                    .startsWith("Полить теплицу")
                    .endsWith("25.08.2026 09:00")
                    .contains("due");
        }
    }

    /**
     * Напоминание неактивному: имя подставляется per-token, поэтому при userName == null
     * fallback-имя тоже обязано быть на языке устройства — иначе английский пользователь
     * получит «друг, ...» в остальном английском тексте.
     */
    @Test
    void sendInactiveReminderPush_NullUserName_UsesPerTokenFallbackName() throws Exception {
        when(pushTokenRepository.findByUserId(7L))
                .thenReturn(List.of(tokenFor(11L, "ru-RU"), tokenFor(12L, "en-US")));

        try (MockedStatic<FirebaseMessaging> mockedFirebaseMessaging = mockStatic(FirebaseMessaging.class)) {
            mockedFirebaseMessaging.when(FirebaseMessaging::getInstance).thenReturn(firebaseMessaging);

            serviceWithRealMessages().sendInactiveReminderPush(7L, null);

            List<Message> sent = sentMessages(2);
            assertThat(sentToken(sent.get(0))).isEqualTo("fcm-token-11");
            assertThat(notificationTitle(sent.get(0))).isEqualTo("Мы скучаем! ✅");
            assertThat(notificationBody(sent.get(0))).startsWith("друг,").contains("списки");
            assertThat(sentToken(sent.get(1))).isEqualTo("fcm-token-12");
            assertThat(notificationTitle(sent.get(1))).isEqualTo("We miss you! ✅");
            assertThat(notificationBody(sent.get(1))).startsWith("friend,").contains("lists");
            // Списка у напоминания нет — deep link вести некуда, и ключей list_* быть не должно.
            assertThat(extractData(sent.get(0)))
                    .containsEntry("push_type", "inactive_reminder")
                    .doesNotContainKey("list_id")
                    .doesNotContainKey("list_name");
        }
    }

    /**
     * Onboarding-напоминание — свой набор ключей ({@code push.onboarding.*}), а не текст
     * inactive-напоминания: фокус на «попробуйте сейчас». Проверяется вместе с per-token
     * fallback-именем, потому что механизм подстановки имени здесь тот же.
     */
    @Test
    void sendOnboardingReminderPush_NullUserName_UsesPerTokenFallbackName() throws Exception {
        when(pushTokenRepository.findByUserId(7L))
                .thenReturn(List.of(tokenFor(11L, "ru-RU"), tokenFor(12L, "en-US")));

        try (MockedStatic<FirebaseMessaging> mockedFirebaseMessaging = mockStatic(FirebaseMessaging.class)) {
            mockedFirebaseMessaging.when(FirebaseMessaging::getInstance).thenReturn(firebaseMessaging);

            serviceWithRealMessages().sendOnboardingReminderPush(7L, null);

            List<Message> sent = sentMessages(2);
            assertThat(sentToken(sent.get(0))).isEqualTo("fcm-token-11");
            assertThat(notificationTitle(sent.get(0))).isEqualTo("Готовы попробовать?");
            assertThat(notificationBody(sent.get(0))).startsWith("друг,").contains("первый список");
            assertThat(sentToken(sent.get(1))).isEqualTo("fcm-token-12");
            assertThat(notificationTitle(sent.get(1))).isEqualTo("Ready to start?");
            assertThat(notificationBody(sent.get(1))).startsWith("friend,").contains("first list");
            assertThat(extractData(sent.get(0)))
                    .containsEntry("push_type", "onboarding_reminder")
                    .doesNotContainKey("list_id")
                    .doesNotContainKey("list_name");
        }
    }

    /**
     * Fallback-имя берётся ТОЛЬКО когда имени нет.
     *
     * Без этой проверки оба теста выше оставались бы зелёными и у кода, который зовёт
     * «другом» всех подряд — включая пользователей, чьё имя мы знаем. Оба напоминания в одном
     * тесте потому, что подстановка имени у них общая: правило одно, точек применения две.
     */
    @Test
    void reminderPushes_WithUserName_UseGivenNameNotFallback() throws Exception {
        when(pushTokenRepository.findByUserId(7L)).thenReturn(List.of(tokenFor(11L, "ru-RU")));

        try (MockedStatic<FirebaseMessaging> mockedFirebaseMessaging = mockStatic(FirebaseMessaging.class)) {
            mockedFirebaseMessaging.when(FirebaseMessaging::getInstance).thenReturn(firebaseMessaging);

            PushNotificationServiceImpl service = serviceWithRealMessages();
            service.sendInactiveReminderPush(7L, "Иван");
            service.sendOnboardingReminderPush(7L, "Иван");

            // Title'ы разные — иначе тест не фиксировал бы, какое из двух сообщений onboarding,
            // и молча прошёл бы, если бы оба напоминания ушли по одним и тем же ключам.
            List<Message> sent = sentMessages(2);
            assertThat(notificationTitle(sent.get(0))).isEqualTo("Мы скучаем! ✅");
            assertThat(notificationTitle(sent.get(1))).isEqualTo("Готовы попробовать?");
            // startsWith достаточно: fallback дал бы «друг, ...». Запрета на слово «друг» в
            // тексте нет намеренно — маркетинг вправе написать «Мы скучаем, друг!».
            assertThat(notificationBody(sent.get(0))).startsWith("Иван,");
            assertThat(notificationBody(sent.get(1))).startsWith("Иван,");
        }
    }

    /**
     * Ключи push-сообщений и арность аргументов, с какой их зовёт прод-код — единый источник
     * для обоих сторожей ниже: прямого (ключ лежит в обоих бандлах и резолвится) и обратного
     * (в бандлах нет push-ключа, которого нет здесь).
     */
    private static final Map<String, Integer> GUARDED_PUSH_KEYS = Map.ofEntries(
            Map.entry("push.todo.created.title", 0), Map.entry("push.todo.created.body", 2),
            Map.entry("push.todo.done.title", 0), Map.entry("push.todo.done.body", 2),
            Map.entry("push.member.added.title", 0), Map.entry("push.member.added.body", 2),
            Map.entry("push.inactive.title", 0), Map.entry("push.inactive.body", 1),
            Map.entry("push.onboarding.title", 0), Map.entry("push.onboarding.body", 1),
            Map.entry("push.todo.due.title", 0), Map.entry("push.todo.due.body", 2),
            Map.entry("push.fallback.name", 0));

    static Stream<Arguments> guardedPushKeys() {
        return GUARDED_PUSH_KEYS.entrySet().stream()
                .map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
    }

    /**
     * Сторож бандлов: каждый ключ, который просит сервис, ЛЕЖИТ в обоих языковых файлах и
     * резолвится при той арности аргументов, с какой ключ зовёт прод.
     * <p>
     * {@link MessageService#getMessage} при отсутствии ключа не бросает, а возвращает САМ КЛЮЧ —
     * то есть опечатка в имени или потерянная при правке {@code .properties} строка уезжает на
     * устройство заголовком вида «push.member.added.title», и ни в логах, ни в тестах поведения
     * этого не видно: у типа, чей текст тест не рендерит, ассертить нечего.
     * <p>
     * Наличие ключа проверяется ПО ФАЙЛУ, а не по результату резолва, потому что резолв не
     * различает два случая, которые различать надо: (1) пропал весь английский файл — поиск
     * уходит на {@code defaultLocale} и возвращает русский текст (замерено на {@code de-DE},
     * у которого бандла нет); (2) ключ заведён только в корневом {@code messages.properties} —
     * он находится через родительскую цепочку, и оба языка молча получают один и тот же текст.
     * Пропажу ОДНОГО ключа из существующего бандла резолв, наоборот, ловит сам: бандл найден,
     * ключа в нём нет, и {@code MessageService} отдаёт имя ключа.
     * <p>
     * Чем он не дублирует {@code EmailServiceImplTest.messageBundles_QuoteApostrophesOnlyWhereMessageFormatRuns}:
     * тот итерирует ключи, которые в файле ЕСТЬ, и потому не видит ключ, пропавший в одном
     * бандле из двух; здесь список идёт от прод-кода, поэтому ловится именно пропажа и опечатка.
     * <p>
     * Арность берётся от ключа, а не «два аргумента всем»: title'ы прод резолвит с пустым
     * массивом, а при пустом массиве MessageFormat не запускается вовсе — значит плейсхолдер,
     * заведённый в title, уехал бы на устройство неподставленным. Проверка на открывающую
     * фигурную скобку ловит и это, и лишний плейсхолдер в body, а различимые значения аргументов
     * («A0», «A1») — потерянный плейсхолдер: при шаблоне без {@code A1} аргумент исчезает молча.
     * Заводишь push-тип — добавляй его ключи сюда с их арностью.
     */
    @ParameterizedTest
    @MethodSource("guardedPushKeys")
    void pushMessageKey_ResolvesToTextInBothBundles(String key, int argCount) {
        MessageService messages = realMessages();
        Object[] args = new Object[argCount];
        Arrays.setAll(args, i -> "A" + i);

        for (String tag : List.of("ru-RU", "en-US")) {
            String language = Locale.forLanguageTag(tag).getLanguage();
            assertThat(bundleKeys(language)).as("ключ %s в messages_%s", key, language).contains(key);

            String text = messages.getMessage(key, Locale.forLanguageTag(tag), args);
            assertThat(text).as("%s @ %s", key, tag)
                    .isNotBlank()
                    .isNotEqualTo(key)
                    .doesNotContain("{");
            for (Object arg : args) {
                assertThat(text).as("аргумент %s у %s @ %s", arg, key, tag).contains((String) arg);
            }
        }
    }

    /**
     * Обратная сторона сторожа: в бандлах нет push-ключа, которого нет в таблице выше.
     * <p>
     * Прямой сторож идёт от таблицы, поэтому ключ, заведённый в коде и в русском бандле, но не
     * внесённый ни в таблицу, ни в англ. бандл, проходит мимо него целиком — а англоязычное
     * устройство получит по такому ключу имя ключа вместо текста. Здесь проверка идёт от
     * бандлов, так что забывчивость перестаёт быть тихой: тест краснеет при правке бандла.
     */
    @Test
    void everyPushKeyInBundlesIsGuarded() {
        for (String language : List.of("ru", "en")) {
            assertThat(bundleKeys(language).stream().filter(key -> key.startsWith("push.")).toList())
                    .as("push-ключи messages_%s", language)
                    .containsExactlyInAnyOrderElementsOf(GUARDED_PUSH_KEYS.keySet());
        }
    }

    /**
     * Ключи бандла как они лежат в ФАЙЛЕ — резолв через {@code MessageService} про содержимое
     * конкретного файла не говорит, он ходит по родительской цепочке и по fallback-локали.
     * Отсутствие самого файла роняет тест сразу — это тот же дефект, только крупнее.
     */
    private static Set<String> bundleKeys(String language) {
        String resource = "/messages_" + language + ".properties";
        Properties bundle = new Properties();
        try (Reader reader = new InputStreamReader(Objects.requireNonNull(
                PushNotificationServiceImplTest.class.getResourceAsStream(resource),
                "нет бандла " + resource), StandardCharsets.UTF_8)) {
            bundle.load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать " + resource, e);
        }
        return bundle.stringPropertyNames();
    }

    /**
     * Токен с тегом {@code und} («язык не определён») получает текст, а не сам ключ.
     * <p>
     * Тег живой: {@code LocaleNormalizer.normalize} возвращает его как есть, а валидация
     * пропускает (см. {@code LocaleNormalizerTest}), так что в {@code push_token.locale} он лежит.
     * Сам по себе {@code Locale.forLanguageTag("und")} даёт {@code Locale.ROOT}, а у ROOT кандидат
     * ровно один — намеренно пустой корневой {@code messages.properties}, — и поиск обрывается,
     * не дойдя до {@code defaultLocale}: на устройство уходило «push.todo.created.title».
     * Проверяются оба места, где сервис резолвит текст по локали токена: заголовок в общей
     * отправке и fallback-имя в напоминаниях.
     */
    @Test
    void undLocale_ReceivesRussianTextNotRawKey() throws Exception {
        when(pushTokenRepository.findByListIdExcludingUser(86L, 53L)).thenReturn(List.of(tokenFor(11L, "und")));
        when(taskListRepository.findById(86L)).thenReturn(Optional.of(listNamed(86L, "Теплица")));
        when(pushTokenRepository.findByUserId(7L)).thenReturn(List.of(tokenFor(12L, "und")));
        String key = "push.todo.created.title";

        try (MockedStatic<FirebaseMessaging> mockedFirebaseMessaging = mockStatic(FirebaseMessaging.class)) {
            mockedFirebaseMessaging.when(FirebaseMessaging::getInstance).thenReturn(firebaseMessaging);

            PushNotificationServiceImpl service = serviceWithRealMessages();
            service.notifyNewTodo(86L, 53L, "Иван", "Хлеб");
            service.sendInactiveReminderPush(7L, null);
            service.sendOnboardingReminderPush(7L, null);

            List<Message> sent = sentMessages(3);
            assertThat(notificationTitle(sent.get(0)))
                    .isEqualTo(realMessages().getMessage(key, Locale.forLanguageTag("ru")))
                    .isNotEqualTo(key);
            assertThat(notificationBody(sent.get(1))).startsWith("друг,");
            assertThat(notificationBody(sent.get(2))).startsWith("друг,");
        }
    }

    /**
     * Сбой резолва текста у одного получателя не обрывает рассылку остальным.
     * <p>
     * Резолв идёт по локали каждого токена, и бросить он может только на своём бандле:
     * кривой шаблон в одном языке — {@code IllegalArgumentException} из {@code MessageFormat}.
     * Каналов два вида: общая отправка (текст резолвится в цикле по токенам) и напоминания
     * (там ещё и fallback-имя резолвится per-token до отправки) — проверяются все.
     */
    @Test
    void textResolveFailureForOneRecipient_OthersStillReceivePush() throws Exception {
        List<PushToken> recipients = List.of(tokenFor(11L, "en"), tokenFor(12L, "ru"));
        when(pushTokenRepository.findByListIdExcludingUser(86L, 53L)).thenReturn(recipients);
        when(pushTokenRepository.findByUserId(7L)).thenReturn(recipients);
        when(taskListRepository.findById(86L)).thenReturn(Optional.of(listNamed(86L, "Теплица")));
        MessageService brokenEnglish = new MessageService(new I18nConfig().messageSource()) {
            @Override
            public String getMessage(String key, Locale locale, Object... args) {
                if ("en".equals(locale.getLanguage())) {
                    throw new IllegalArgumentException("can't parse argument number: name");
                }
                return super.getMessage(key, locale, args);
            }
        };
        PushNotificationServiceImpl service = new PushNotificationServiceImpl(pushTokenRepository,
                taskListRepository, flagStore, brokenEnglish);

        try (MockedStatic<FirebaseMessaging> mockedFirebaseMessaging = mockStatic(FirebaseMessaging.class)) {
            mockedFirebaseMessaging.when(FirebaseMessaging::getInstance).thenReturn(firebaseMessaging);

            assertThatCode(() -> {
                service.notifyNewTodo(86L, 53L, "Иван", "Хлеб");
                service.notifyTodoCompleted(53L, 86L, "Иван", "Хлеб");
                service.notifyNewMember(86L, 53L, "Иван", "Теплица");
                service.sendTodoDuePush(7L, 777L, 86L, "Полить теплицу", "25.08.2026 09:00");
                service.sendInactiveReminderPush(7L, null);
                service.sendOnboardingReminderPush(7L, null);
            }).doesNotThrowAnyException();

            // Шесть каналов — по одному сообщению каждый, и все на русское устройство.
            assertThat(sentMessages(6)).allSatisfy(message ->
                    assertThat(sentToken(message)).isEqualTo("fcm-token-12"));
        }
    }

    /** Чужой device_id не удаляет токен: отвязать устройство может только его владелец. */
    @Test
    void removeToken_ForeignDevice_KeepsToken() {
        PushToken foreign = tokenFor(11L, "ru");
        when(pushTokenRepository.findByDeviceId("device-11")).thenReturn(Optional.of(foreign));

        pushNotificationService.removeToken(99L, "device-11");

        verify(pushTokenRepository, never()).delete(any());
    }

    /** Своё устройство отвязывается — иначе тест выше прошёл бы и у метода, не удаляющего ничего. */
    @Test
    void removeToken_OwnDevice_DeletesToken() {
        PushToken own = tokenFor(11L, "ru");
        when(pushTokenRepository.findByDeviceId("device-11")).thenReturn(Optional.of(own));

        pushNotificationService.removeToken(11L, "device-11");

        verify(pushTokenRepository).delete(own);
    }

    /**
     * {@code isFirebaseHealthy} отдаёт результат последней {@code checkFirebaseHealth}, а не
     * константу: поднятый Firebase переводит кеш в true, упавший — обратно в false.
     */
    @Test
    void isFirebaseHealthy_FollowsLastHealthCheck() {
        assertThat(pushNotificationService.isFirebaseHealthy()).isFalse();

        try (MockedStatic<com.google.firebase.FirebaseApp> mockedApp = mockStatic(com.google.firebase.FirebaseApp.class);
             MockedStatic<FirebaseMessaging> mockedFirebaseMessaging = mockStatic(FirebaseMessaging.class)) {
            mockedFirebaseMessaging.when(FirebaseMessaging::getInstance).thenReturn(firebaseMessaging);
            mockedApp.when(com.google.firebase.FirebaseApp::getInstance).thenReturn(null);

            pushNotificationService.checkFirebaseHealth();
            assertThat(pushNotificationService.isFirebaseHealthy()).isTrue();

            mockedApp.when(com.google.firebase.FirebaseApp::getInstance)
                    .thenThrow(new IllegalStateException("FirebaseApp with name [DEFAULT] doesn't exist."));

            pushNotificationService.checkFirebaseHealth();
            assertThat(pushNotificationService.isFirebaseHealthy()).isFalse();
        }
    }

    /**
     * Локаль, у которой язык вычитывается, а своего бандла нет, обслуживается русским текстом,
     * а не сырыми ключами. Про тег без языка — тест выше: он сводится к русскому явно.
     * <p>
     * Путь живой: валидация принимает любой язык из двух-трёх букв, а {@code LocaleNormalizer}
     * регион сохраняет — в {@code push_token.locale} реально уезжают {@code de-DE}, {@code zh-CN},
     * {@code pt-BR}. Держит это {@code setDefaultLocale(ru)} в {@code I18nConfig}: без него
     * бандлом для такого тега становится пустой корневой {@code messages.properties}, ключ не
     * находится, и на устройство уходит уведомление с заголовком «push.todo.created.title».
     * Остальные тесты этого не заметят — {@code ru-RU} и {@code en-US} резолвятся своими бандлами.
     * <p>
     * Сравнение идёт с русским резолвом, а не с литералом текста: предмет теста — механизм
     * fallback'а, и правка самого заголовка push'а ронять его не должна. Ассерт на «это не имя
     * ключа» нужен, иначе оба резолва, вернувшие ключ, совпали бы друг с другом.
     */
    @Test
    void localeWithoutBundle_FallsBackToRussianText() {
        MessageService messages = realMessages();
        String key = "push.todo.created.title";

        assertThat(messages.getMessage(key, Locale.forLanguageTag("de-DE")))
                .isEqualTo(messages.getMessage(key, Locale.forLanguageTag("ru")))
                .isNotEqualTo(key);
    }

    /**
     * Глобальный рубильник гасит ВСЕ видимые каналы, а не только тихую синхронизацию.
     * <p>
     * Флаг — аварийный рычаг на случай нестабильной работы Firebase, и держится он только
     * гардом внутри каждого метода сервиса: вызывающая сторона о флаге не знает —
     * {@code TodoServiceImpl} зовёт {@code sendTodoDuePush} безусловно. До этого теста гард был
     * покрыт у одного канала из семи, то есть удаление его в любом другом оставляло прогон
     * зелёным.
     * <p>
     * Проверяется РЕПОЗИТОРИЙ, а не мок FirebaseMessaging: без mockStatic тот с прод-кодом не
     * связан вовсе и упасть не может, а получателей каждый метод спрашивает сразу после гарда.
     */
    @Test
    void allVisiblePushChannels_GloballyDisabled_DoNotEvenLookUpTokens() {
        when(flagStore.isEnabled(FeatureFlag.PUSH_NOTIFICATIONS)).thenReturn(false);

        pushNotificationService.notifyNewTodo(86L, 53L, "Иван", "Хлеб");
        pushNotificationService.notifyTodoCompleted(53L, 86L, "Иван", "Хлеб");
        pushNotificationService.notifyNewMember(86L, 53L, "Иван", "Теплица");
        pushNotificationService.sendInactiveReminderPush(7L, "Иван");
        pushNotificationService.sendOnboardingReminderPush(7L, "Иван");
        pushNotificationService.sendTodoDuePush(7L, 777L, 86L, "Полить теплицу", "25.08.2026 09:00");

        verifyNoInteractions(pushTokenRepository);
    }

    /**
     * MessageService на ПРОД-конфигурации — {@code I18nConfig} зовётся, а не копируется руками:
     * копия молча разошлась бы с продом при правке basename, defaultLocale или fallback, и тесты
     * остались бы зелёными, пока прод отдаёт клиенту сырые ключи. Тот же приём и по той же
     * причине — в {@code EmailServiceImplTest}.
     */
    private static MessageService realMessages() {
        return new MessageService(new I18nConfig().messageSource());
    }

    /**
     * Сервис с настоящими текстами из {@code messages*.properties}. Моком MessageService тут не
     * обойтись: он проверяет только «какой ключ с какой локалью запросили», а текст возвращает
     * null — то есть пропустил бы и опечатку в имени ключа (сервис отдал бы клиенту сам ключ),
     * и пустой бандл. Остальные зависимости — те же моки, что у {@code @InjectMocks}-экземпляра.
     */
    private PushNotificationServiceImpl serviceWithRealMessages() {
        return new PushNotificationServiceImpl(pushTokenRepository,
                taskListRepository, flagStore, realMessages());
    }

    /** Сообщения, ушедшие в FCM поштучно, в порядке отправки — по одному на токен. */
    private List<Message> sentMessages(int expectedCount) throws FirebaseMessagingException {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(firebaseMessaging, times(expectedCount)).send(captor.capture());
        return captor.getAllValues();
    }

    /** Токен, на который ушло сообщение — связка «этот текст → это устройство». */
    private String sentToken(Message message) {
        return (String) readField(message, "token");
    }

    /** Минимальный список — нужен только чтобы sendLocalized подставил list_name. */
    private TaskList listNamed(Long id, String name) {
        TaskList list = new TaskList();
        list.setId(id);
        list.setName(name);
        return list;
    }

    /**
     * AndroidNotification собранного Message — читается отражением, потому что публичных
     * читателей у AndroidConfig и AndroidNotification нет вовсе, только поля (в отличие от
     * {@code Message.getData()}, который package-private). Возвращает null, если сообщение
     * собрано без notification-payload (тихая синхронизация).
     */
    private Object androidNotification(Message message) {
        return readField(readField(message, "androidConfig"), "notification");
    }

    /** Title из AndroidNotification. */
    private String notificationTitle(Message message) {
        return (String) readField(androidNotification(message), "title");
    }

    /** Body из AndroidNotification — там же, где title. */
    private String notificationBody(Message message) {
        return (String) readField(androidNotification(message), "body");
    }

    /**
     * Проверяет, что AndroidNotification вообще собран и канал тот, который заводит клиент
     * (`TodoApp.createNotificationChannel`). Без канала система роняет уведомление в
     * fallback-канал FCM SDK — этот дефект в проекте уже был.
     */
    private void assertNotificationConfigured(Message message) {
        assertThat(androidNotification(message)).isNotNull();
        assertThat((String) readField(androidNotification(message), "channelId"))
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
     * Tag из AndroidNotification. Возвращает null и когда tag не задан, и когда отсутствует
     * сам AndroidNotification — поэтому проверять только его недостаточно, см.
     * assertNotificationConfigured.
     */
    private String extractNotificationTag(Message message) {
        return (String) readField(androidNotification(message), "tag");
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
    @SuppressWarnings("unchecked")
    private Map<String, String> extractData(Message message) {
        return (Map<String, String>) readField(message, "data");
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
