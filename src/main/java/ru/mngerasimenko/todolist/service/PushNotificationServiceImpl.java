package ru.mngerasimenko.todolist.service;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.SendResponse;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mngerasimenko.todolist.exception.UserNotFoundException;
import ru.mngerasimenko.todolist.featureflags.FeatureFlag;
import ru.mngerasimenko.todolist.featureflags.FeatureFlagStore;
import ru.mngerasimenko.todolist.model.PushToken;
import ru.mngerasimenko.todolist.repository.PushTokenRepository;
import ru.mngerasimenko.todolist.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Реализация сервиса push-уведомлений через Firebase Cloud Messaging.
 * Все отправки выполняются асинхронно (@Async), чтобы не блокировать основной запрос.
 * Отправка полностью подавляется, если выключен {@link FeatureFlag#PUSH_NOTIFICATIONS}
 * (runtime toggle на случай нестабильной работы Firebase).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushNotificationServiceImpl implements PushNotificationService {

    private final PushTokenRepository pushTokenRepository;
    private final UserRepository userRepository;
    private final ru.mngerasimenko.todolist.repository.TaskListRepository taskListRepository;
    private final FeatureFlagStore flagStore;
    private final MessageService messageService;

    /**
     * Значения для FCM data-поля {@code push_type} (Phase 3.1-server).
     * <p>
     * Заводились как маркер для аналитики, но у поля уже есть ОДИН реальный потребитель на
     * клиенте: {@code todo_due} открывает по тапу саму задачу, а не список. Остальные типы,
     * включая {@code todo_updated}, Android по типу не разбирает — он реагирует на наличие
     * {@code list_id}, а тишина {@code todo_updated} обеспечивается отсутствием
     * notification-payload, а не типом.
     */
    public static final String PUSH_TYPE_TASK_ADDED = "task_added";
    public static final String PUSH_TYPE_TASK_COMPLETED = "task_completed";
    public static final String PUSH_TYPE_MEMBER_ADDED = "member_added";
    public static final String PUSH_TYPE_INACTIVE_REMINDER = "inactive_reminder";
    public static final String PUSH_TYPE_ONBOARDING_REMINDER = "onboarding_reminder";
    public static final String PUSH_TYPE_TODO_DUE = "todo_due";
    /** Тихая синхронизация: клиент перечитывает список и ничего не показывает. */
    public static final String PUSH_TYPE_TODO_UPDATED = "todo_updated";
    /** Общий ключ схлопывания для тихой синхронизации — см. {@link #notifyTodoUpdated}. */
    private static final String SYNC_COLLAPSE_KEY = "todo_sync";
    /** Час: протухшая синхронизация бесполезна, список всё равно перечитывается при открытии. */
    private static final long SYNC_TTL_MILLIS = 3_600_000L;
    /** Потолок FCM на один multicast-вызов; больше — {@code IllegalArgumentException} из build(). */
    private static final int MULTICAST_BATCH_LIMIT = 500;

    /** Кешированный результат проверки Firebase */
    private volatile boolean firebaseHealthyCache = false;

    @Override
    @Transactional
    public void registerToken(Long userId, String fcmToken, String deviceId, String locale) {
        // Fallback на "ru" для старых Android-клиентов, которые не шлют поле locale
        String effectiveLocale = (locale == null || locale.isBlank()) ? "ru" : locale;

        // Атомарный upsert вместо findByDeviceId + save — две одновременные регистрации
        // того же устройства больше не вызывают DataIntegrityViolationException или потерю
        // обновления при race condition (путь стал hot после Phase A.4 R-3 — Android-клиент
        // перерегистрирует токен при каждой смене языка в Settings).
        //
        // FK-нарушение на user_id ловим единственно возможной DataIntegrityViolationException
        // на этой операции (никаких других нарушений быть не может — device_id UNIQUE решён
        // самим ON CONFLICT). Это избавляет от лишнего round-trip в existsById и от TOCTOU
        // окна между ним и upsert'ом.
        try {
            pushTokenRepository.upsertByDeviceId(userId, fcmToken, deviceId, effectiveLocale);
        } catch (DataIntegrityViolationException e) {
            throw new UserNotFoundException("User not found: " + userId);
        }
        log.info("Upsert push-токена: deviceId={}, userId={}, locale={}", deviceId, userId, effectiveLocale);
    }

    @Override
    @Transactional
    public void removeToken(Long userId, String deviceId) {
        pushTokenRepository.findByDeviceId(deviceId).ifPresent(pt -> {
            if (pt.getUser().getId().equals(userId)) {
                pushTokenRepository.delete(pt);
                log.info("Удалён push-токен: deviceId={}, userId={}", deviceId, userId);
            }
        });
    }

    @Override
    @Async
    public void notifyNewTodo(Long listId, Long authorUserId, String authorName, String todoName) {
        if (pushDisabled()) return;
        log.info("Отправка push: новая задача '{}' в списке {}, автор userId={}", todoName, listId, authorUserId);
        List<PushToken> tokens = pushTokenRepository.findByListIdExcludingUser(listId, authorUserId);
        log.info("Найдено {} push-токенов для уведомления", tokens.size());
        if (tokens.isEmpty()) return;

        sendLocalized(tokens, PUSH_TYPE_TASK_ADDED,
                "push.todo.created.title", new Object[]{},
                "push.todo.created.body", new Object[]{authorName, todoName},
                listId);
    }

    @Override
    @Async
    public void notifyTodoCompleted(Long completorUserId, Long listId, String completorName, String todoName) {
        if (pushDisabled()) return;
        log.info("Отправка push: задача '{}' выполнена пользователем '{}' в списке {}", todoName, completorName, listId);
        List<PushToken> tokens = pushTokenRepository.findByListIdExcludingUser(listId, completorUserId);
        log.info("Найдено {} push-токенов для уведомления", tokens.size());
        if (tokens.isEmpty()) return;

        sendLocalized(tokens, PUSH_TYPE_TASK_COMPLETED,
                "push.todo.done.title", new Object[]{},
                "push.todo.done.body", new Object[]{completorName, todoName},
                listId);
    }

    @Override
    @Async
    public void notifyTodoUpdated(Long listId, Long editorUserId, Long todoId) {
        if (pushDisabled()) return;
        if (!flagStore.isEnabled(FeatureFlag.TODO_UPDATE_SYNC_PUSH)) return;
        // Редактора НЕ исключаем, в отличие от видимых уведомлений: сообщение ничего не
        // показывает, а исключение идёт по пользователю — то есть отсекло бы и второе
        // устройство самого редактора, оставив его со строкой «до правки».
        List<PushToken> tokens = pushTokenRepository.findByListId(listId);
        if (tokens.isEmpty()) return;
        log.debug("Отправка sync-push: задача {} в списке {} изменена userId={}, получателей {}",
                todoId, listId, editorUserId, tokens.size());

        // Ни notification, ни AndroidNotification: сообщение обязано остаться невидимым. Стоит
        // добавить сюда notification-payload — и каждая правка чужой задачи начнёт звенеть у
        // всех участников.
        //
        // collapseKey и ttl — не оптимизация, а защита СОСЕДНИХ уведомлений. Сообщения без
        // collapse key складываются в очередь офлайн-устройства, её потолок — сотня, и при
        // переполнении FCM выбрасывает ВСЮ очередь целиком. Этот канал самый частый из наших
        // (по сообщению на каждую правку каждому участнику), а в той же очереди лежит
        // напоминание о сроке — то самое, ради которого сделана вся ветка. Ключ общий, а не
        // на список: у FCM потолок в четыре разных ключа на устройство, а участник легко
        // состоит в большем числе списков. Цена — при офлайне доедет синхронизация только по
        // последнему изменённому списку; остальные всё равно перечитываются при открытии.
        // ⚠️ Схлопывание работает ТОЛЬКО для очереди офлайн-устройства. Онлайновый получатель
        // получает каждое сообщение отдельно и на каждое тянет весь список — серия быстрых
        // правок сериями и прилетит. Ограничителя частоты здесь нет, только флаг.
        //
        // Приоритет НЕ высокий, и это осознанно. HIGH разбудил бы устройство в Doze, но выигрыш
        // мнимый: обновление на клиенте запускается незавершаемой корутиной и при закрытом
        // приложении всё равно может не доработать. А цена реальна — FCM понижает приоритет
        // приложению, которое часто шлёт high-priority сообщения без взаимодействия с
        // пользователем, и понижение затронуло бы напоминания о сроке. На доставку в foreground,
        // где обновление и работает, приоритет не влияет.
        AndroidConfig androidConfig = AndroidConfig.builder()
                .setPriority(AndroidConfig.Priority.NORMAL)
                .setCollapseKey(SYNC_COLLAPSE_KEY)
                .setTtl(SYNC_TTL_MILLIS)
                .build();

        // Нарезка обязательна: build() у MulticastMessage сам отбивает больше 500 токенов
        // IllegalArgumentException'ом, и без неё большой список не получал бы синхронизацию
        // вовсе. Сборка внутри try по той же причине — исключение из build() иначе улетало бы
        // мимо нашего лога, в обработчик необработанных исключений @Async.
        for (int offset = 0; offset < tokens.size(); offset += MULTICAST_BATCH_LIMIT) {
            List<PushToken> batch = tokens.subList(offset,
                    Math.min(offset + MULTICAST_BATCH_LIMIT, tokens.size()));
            try {
                MulticastMessage message = MulticastMessage.builder()
                        .addAllTokens(batch.stream().map(PushToken::getFcmToken).toList())
                        .setAndroidConfig(androidConfig)
                        .putData("push_type", PUSH_TYPE_TODO_UPDATED)
                        .putData("list_id", String.valueOf(listId))
                        .build();

                // Запросов по-прежнему N — sendEachForMulticast внутри разворачивает пачку в
                // отдельный вызов на токен. Разница в том, ГДЕ они выполняются: параллельно на
                // собственном (ленивом) пуле Firebase, а наш @Async-поток блокируется один раз на
                // всю пачку, а не N раз подряд. Пул @Async общий с отправкой писем, и занимать
                // его последовательными HTTPS-вызовами дороже. Payload у всех одинаковый — в
                // отличие от sendLocalized, где текст рендерится под локаль каждого токена.
                BatchResponse response = FirebaseMessaging.getInstance().sendEachForMulticast(message);
                // Порядок ответов совпадает с порядком токенов — на этом и держится связка ниже.
                for (int i = 0; i < response.getResponses().size(); i++) {
                    SendResponse sendResponse = response.getResponses().get(i);
                    if (sendResponse.isSuccessful()) continue;
                    FirebaseMessagingException e = sendResponse.getException();
                    if (e != null && e.getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED) {
                        // Свой try: fcm_token в схеме НЕ уникален (уникален device_id), и дубль
                        // уронил бы findByFcmToken — вместе с чисткой остальных токенов пачки.
                        try {
                            pushTokenRepository.findByFcmToken(batch.get(i).getFcmToken())
                                    .ifPresent(deadToken -> {
                                        pushTokenRepository.delete(deadToken);
                                        log.info("Удалён невалидный push-токен для устройства: {}",
                                                deadToken.getDeviceId());
                                    });
                        } catch (RuntimeException ex) {
                            log.warn("Не удалось убрать невалидный токен: {}", ex.toString());
                        }
                    } else {
                        log.warn("Ошибка отправки sync-push: {}", e != null ? e.toString() : "неизвестно");
                    }
                }
            } catch (Exception e) {
                log.warn("Не удалось отправить sync-push по списку {}: {}", listId, e.toString());
            }
        }
    }

    @Override
    @Async
    public void notifyNewMember(Long listId, Long newUserId, String newUserName, String listName) {
        if (pushDisabled()) return;
        log.info("Отправка push: новый участник '{}' в списке {} ('{}')", newUserName, listId, listName);
        List<PushToken> tokens = pushTokenRepository.findByListIdExcludingUser(listId, newUserId);
        log.info("Найдено {} push-токенов для уведомления", tokens.size());
        if (tokens.isEmpty()) return;

        sendLocalized(tokens, PUSH_TYPE_MEMBER_ADDED,
                "push.member.added.title", new Object[]{},
                "push.member.added.body", new Object[]{newUserName, listName},
                listId);
    }

    @Override
    public boolean isFirebaseHealthy() {
        return firebaseHealthyCache;
    }

    @Override
    public void checkFirebaseHealth() {
        try {
            // Проверяем что FirebaseApp инициализирован
            FirebaseApp.getInstance();
            // Проверяем что FirebaseMessaging доступен
            FirebaseMessaging.getInstance();
            firebaseHealthyCache = true;
        } catch (Exception e) {
            log.warn("Firebase health check failed: {}", e.getMessage());
            firebaseHealthyCache = false;
        }
    }

    /**
     * Отправить локализованный push на несколько устройств. Title/body для каждого
     * токена рендерятся через {@link MessageService} с использованием его персональной
     * {@code locale} (BCP-47, см. {@link PushToken#getLocale()}).
     * <p>
     * Невалидные токены (UNREGISTERED) автоматически удаляются.
     */
    private void sendLocalized(List<PushToken> tokens,
                               String pushType,
                               String titleKey, Object[] titleArgs,
                               String bodyKey, Object[] bodyArgs,
                               Long listId) {
        sendLocalized(tokens, pushType, titleKey, titleArgs, bodyKey, bodyArgs, listId, Map.of(), null);
    }

    /**
     * Перегрузка с произвольными дополнительными data-полями (например, {@code todo_id}
     * у {@link #sendTodoDuePush}), которые не укладываются в общий {@code list_id}/{@code list_name}.
     */
    private void sendLocalized(List<PushToken> tokens,
                               String pushType,
                               String titleKey, Object[] titleArgs,
                               String bodyKey, Object[] bodyArgs,
                               Long listId,
                               Map<String, String> extraData,
                               String notificationTag) {
        String listName = listId != null
                ? taskListRepository.findById(listId).map(list -> list.getName()).orElse("")
                : "";

        for (PushToken pt : tokens) {
            Locale locale = Locale.forLanguageTag(pt.getLocale());
            String title = messageService.getMessage(titleKey, locale, titleArgs);
            String body = messageService.getMessage(bodyKey, locale, bodyArgs);
            String fcmToken = pt.getFcmToken();
            try {
                // Tag — часть контракта с Android-клиентом, а не косметика. При закрытом
                // приложении onMessageReceived НЕ вызывается: уведомление рисует сам FCM SDK
                // через notify(tag, 0, ...). Без нашего tag он подставляет свой,
                // "FCM-Notification:<uptime>", уникальный на каждое сообщение — и тогда
                // (а) повторное напоминание по той же задаче ложится РЯДОМ со старым, где
                // стоит уже неверное время, и (б) клиент не может снять уведомление, потому
                // что не знает его адрес. Детерминированный tag чинит оба: система сама
                // заменяет уведомление с тем же tag, а клиент снимает его
                // cancel("todo_due_<id>", 0) — см. ReminderNotifications.tagFor в
                // todolist-android. Менять формат в одиночку нельзя, только парой.
                var androidNotification = com.google.firebase.messaging.AndroidNotification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .setChannelId("todo_notifications_v2");
                if (notificationTag != null) {
                    androidNotification.setTag(notificationTag);
                }

                Message.Builder messageBuilder = Message.builder()
                        .setToken(fcmToken)
                        .setAndroidConfig(com.google.firebase.messaging.AndroidConfig.builder()
                                .setNotification(androidNotification.build())
                                .build())
                        // Phase 3.1-server: семантический маркер типа для будущей аналитики
                        // (Android-парсинг отложен до явного потребителя, см. fromIdeas/
                        //  response_push_typization_phase31_2026-05-17.md).
                        .putData("push_type", pushType);

                if (listId != null) {
                    messageBuilder.putData("list_id", String.valueOf(listId));
                    messageBuilder.putData("list_name", listName);
                }

                extraData.forEach(messageBuilder::putData);

                Message message = messageBuilder.build();

                FirebaseMessaging.getInstance().send(message);
            } catch (FirebaseMessagingException e) {
                if (e.getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED) {
                    // Токен невалиден — устройство удалило приложение или токен обновился
                    pushTokenRepository.findByFcmToken(fcmToken).ifPresent(deadToken -> {
                        pushTokenRepository.delete(deadToken);
                        log.info("Удалён невалидный push-токен для устройства: {}", deadToken.getDeviceId());
                    });
                } else {
                    log.warn("Ошибка отправки push: {}", e.getMessage());
                }
            }
        }
    }

    @Override
    @Async
    public void sendInactiveReminderPush(Long userId, String userName) {
        if (pushDisabled()) return;

        List<PushToken> tokens = pushTokenRepository.findByUserId(userId);
        if (tokens.isEmpty()) {
            log.debug("Нет push-токенов для userId={}, напоминание не отправлено", userId);
            return;
        }

        // Имя локализуется per-token: если userName == null, fallback name берётся
        // на языке каждого устройства (push.fallback.name). Поэтому для каждого
        // токена строим body отдельно через одиночный sendLocalized.
        for (PushToken pt : tokens) {
            Locale locale = Locale.forLanguageTag(pt.getLocale());
            String displayName = userName != null
                    ? userName
                    : messageService.getMessage("push.fallback.name", locale);
            sendLocalized(
                    List.of(pt), PUSH_TYPE_INACTIVE_REMINDER,
                    "push.inactive.title", new Object[]{},
                    "push.inactive.body", new Object[]{displayName},
                    null);
        }
        log.info("Push-напоминание отправлено userId={} на {} устройств(а)", userId, tokens.size());
    }

    @Override
    @Async
    public void sendOnboardingReminderPush(Long userId, String userName) {
        if (pushDisabled()) return;

        List<PushToken> tokens = pushTokenRepository.findByUserId(userId);
        if (tokens.isEmpty()) {
            log.debug("Нет push-токенов для userId={}, onboarding-напоминание не отправлено", userId);
            return;
        }

        // Per-token локализация имени, как в sendInactiveReminderPush.
        for (PushToken pt : tokens) {
            Locale locale = Locale.forLanguageTag(pt.getLocale());
            String displayName = userName != null
                    ? userName
                    : messageService.getMessage("push.fallback.name", locale);
            sendLocalized(
                    List.of(pt), PUSH_TYPE_ONBOARDING_REMINDER,
                    "push.onboarding.title", new Object[]{},
                    "push.onboarding.body", new Object[]{displayName},
                    null);
        }
        log.info("Onboarding push-напоминание отправлено userId={} на {} устройств(а)", userId, tokens.size());
    }

    /**
     * Tag уведомления о сроке задачи. Формат — часть контракта с Android-клиентом
     * ({@code ReminderNotifications.tagFor} в todolist-android): по нему клиент снимает
     * уведомление, когда пользователь с задачей разобрался, а система по нему же заменяет
     * предыдущее напоминание по той же задаче. Менять только одновременно с клиентом.
     */
    static String todoDueNotificationTag(Long todoId) {
        return "todo_due_" + todoId;
    }

    @Override
    @Async
    public void sendTodoDuePush(Long userId, Long todoId, Long listId, String todoName, String dueAt) {
        if (pushDisabled()) return;

        List<PushToken> tokens = pushTokenRepository.findByUserId(userId);
        if (tokens.isEmpty()) {
            log.debug("Нет push-токенов для userId={}, напоминание о сроке не отправлено", userId);
            return;
        }

        // Текст не зависит от токена (без per-token fallback-имени) — рассылаем одним вызовом,
        // sendLocalized сам резолвит locale для каждого токена. listId передаётся как обычный
        // параметр (не extraData) — так переиспользуется существующий механизм list_id/list_name,
        // общий с остальными 5 push-типами (сетевой контракт: push_list_id — внутренний
        // Intent-ключ Android между двумя классами, на проводе его нет).
        sendLocalized(tokens, PUSH_TYPE_TODO_DUE,
                "push.todo.due.title", new Object[]{},
                "push.todo.due.body", new Object[]{todoName, dueAt},
                listId,
                Map.of("todo_id", String.valueOf(todoId)),
                todoDueNotificationTag(todoId));

        log.info("Push-напоминание о сроке отправлено userId={}, todoId={} на {} устройств(а)", userId, todoId, tokens.size());
    }

    /** Короткий helper: true если отправку push нужно пропустить. */
    private boolean pushDisabled() {
        if (!flagStore.isEnabled(FeatureFlag.PUSH_NOTIFICATIONS)) {
            log.debug("Push-уведомления отключены через feature flag");
            return true;
        }
        return false;
    }
}
