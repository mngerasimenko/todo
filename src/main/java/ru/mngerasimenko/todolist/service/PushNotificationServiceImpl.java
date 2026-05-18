package ru.mngerasimenko.todolist.service;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mngerasimenko.todolist.featureflags.FeatureFlag;
import ru.mngerasimenko.todolist.featureflags.FeatureFlagStore;
import ru.mngerasimenko.todolist.model.PushToken;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.repository.PushTokenRepository;
import ru.mngerasimenko.todolist.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

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
     * Значения для FCM data-поля {@code push_type} (Phase 3.1-server). Android-клиент сможет
     * сегментировать события по типу когда подключит парсинг (Android-часть Phase 3.1 отложена
     * до явного потребителя, см. fromIdeas/response_push_typization_phase31_2026-05-17.md).
     */
    public static final String PUSH_TYPE_TASK_ADDED = "task_added";
    public static final String PUSH_TYPE_TASK_COMPLETED = "task_completed";
    public static final String PUSH_TYPE_MEMBER_ADDED = "member_added";
    public static final String PUSH_TYPE_INACTIVE_REMINDER = "inactive_reminder";
    public static final String PUSH_TYPE_ONBOARDING_REMINDER = "onboarding_reminder";

    /** Кешированный результат проверки Firebase */
    private volatile boolean firebaseHealthyCache = false;

    @Override
    @Transactional
    public void registerToken(Long userId, String fcmToken, String deviceId, String locale) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));

        // Fallback на "ru" для старых Android-клиентов, которые не шлют поле locale
        String effectiveLocale = (locale == null || locale.isBlank()) ? "ru" : locale;

        PushToken pushToken = pushTokenRepository.findByDeviceId(deviceId)
                .orElse(null);

        if (pushToken != null) {
            // Обновляем существующий токен (устройство могло сменить пользователя, токен или язык)
            pushToken.setUser(user);
            pushToken.setFcmToken(fcmToken);
            pushToken.setLocale(effectiveLocale);
            pushToken.setUpdatedAt(LocalDateTime.now());
            pushTokenRepository.save(pushToken);
            log.info("Обновлён push-токен для устройства: deviceId={}, userId={}, locale={}", deviceId, userId, effectiveLocale);
        } else {
            // Новое устройство
            pushToken = new PushToken(user, fcmToken, deviceId, effectiveLocale);
            pushTokenRepository.save(pushToken);
            log.info("Зарегистрирован push-токен: deviceId={}, userId={}, locale={}", deviceId, userId, effectiveLocale);
        }
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
        String listName = listId != null
                ? taskListRepository.findById(listId).map(list -> list.getName()).orElse("")
                : "";

        for (PushToken pt : tokens) {
            Locale locale = Locale.forLanguageTag(pt.getLocale());
            String title = messageService.getMessage(titleKey, locale, titleArgs);
            String body = messageService.getMessage(bodyKey, locale, bodyArgs);
            String fcmToken = pt.getFcmToken();
            try {
                Message.Builder messageBuilder = Message.builder()
                        .setToken(fcmToken)
                        .setAndroidConfig(com.google.firebase.messaging.AndroidConfig.builder()
                                .setNotification(com.google.firebase.messaging.AndroidNotification.builder()
                                        .setTitle(title)
                                        .setBody(body)
                                        .setChannelId("todo_notifications_v2")
                                        .build())
                                .build())
                        // Phase 3.1-server: семантический маркер типа для будущей аналитики
                        // (Android-парсинг отложен до явного потребителя, см. fromIdeas/
                        //  response_push_typization_phase31_2026-05-17.md).
                        .putData("push_type", pushType);

                if (listId != null) {
                    messageBuilder.putData("list_id", String.valueOf(listId));
                    messageBuilder.putData("list_name", listName);
                }

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

    /** Короткий helper: true если отправку push нужно пропустить. */
    private boolean pushDisabled() {
        if (!flagStore.isEnabled(FeatureFlag.PUSH_NOTIFICATIONS)) {
            log.debug("Push-уведомления отключены через feature flag");
            return true;
        }
        return false;
    }
}
