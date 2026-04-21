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

    /** Кешированный результат проверки Firebase */
    private volatile boolean firebaseHealthyCache = false;

    @Override
    @Transactional
    public void registerToken(Long userId, String fcmToken, String deviceId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));

        PushToken pushToken = pushTokenRepository.findByDeviceId(deviceId)
                .orElse(null);

        if (pushToken != null) {
            // Обновляем существующий токен (устройство могло сменить пользователя или токен)
            pushToken.setUser(user);
            pushToken.setFcmToken(fcmToken);
            pushToken.setUpdatedAt(LocalDateTime.now());
            pushTokenRepository.save(pushToken);
            log.info("Обновлён push-токен для устройства: deviceId={}, userId={}", deviceId, userId);
        } else {
            // Новое устройство
            pushToken = new PushToken(user, fcmToken, deviceId);
            pushTokenRepository.save(pushToken);
            log.info("Зарегистрирован push-токен: deviceId={}, userId={}", deviceId, userId);
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
        List<String> tokens = pushTokenRepository.findFcmTokensByListIdExcludingUser(listId, authorUserId);
        log.info("Найдено {} push-токенов для уведомления", tokens.size());
        if (tokens.isEmpty()) return;

        sendToMultiple(tokens, "Новая задача", authorName + " добавил: \"" + todoName + "\"", listId);
    }

    @Override
    @Async
    public void notifyTodoCompleted(Long completorUserId, Long listId, String completorName, String todoName) {
        if (pushDisabled()) return;
        log.info("Отправка push: задача '{}' выполнена пользователем '{}' в списке {}", todoName, completorName, listId);
        List<String> tokens = pushTokenRepository.findFcmTokensByListIdExcludingUser(listId, completorUserId);
        log.info("Найдено {} push-токенов для уведомления", tokens.size());
        if (tokens.isEmpty()) return;

        sendToMultiple(tokens, "Задача выполнена ✓", completorName + " выполнил: \"" + todoName + "\"", listId);
    }

    @Override
    @Async
    public void notifyNewMember(Long listId, Long newUserId, String newUserName, String listName) {
        if (pushDisabled()) return;
        log.info("Отправка push: новый участник '{}' в списке {} ('{}')", newUserName, listId, listName);
        List<String> tokens = pushTokenRepository.findFcmTokensByListIdExcludingUser(listId, newUserId);
        log.info("Найдено {} push-токенов для уведомления", tokens.size());
        if (tokens.isEmpty()) return;

        sendToMultiple(tokens, "Новый участник", newUserName + " присоединился к списку \"" + listName + "\"", listId);
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
     * Отправить push на несколько устройств с данными о списке.
     * Невалидные токены (UNREGISTERED) автоматически удаляются.
     */
    private void sendToMultiple(List<String> fcmTokens, String title, String body, Long listId) {
        String listName = listId != null
                ? taskListRepository.findById(listId).map(list -> list.getName()).orElse("")
                : "";

        for (String token : fcmTokens) {
            try {
                Message.Builder messageBuilder = Message.builder()
                        .setToken(token)
                        .setAndroidConfig(com.google.firebase.messaging.AndroidConfig.builder()
                                .setNotification(com.google.firebase.messaging.AndroidNotification.builder()
                                        .setTitle(title)
                                        .setBody(body)
                                        .setChannelId("todo_notifications_v2")
                                        .build())
                                .build());

                if (listId != null) {
                    messageBuilder.putData("list_id", String.valueOf(listId));
                    messageBuilder.putData("list_name", listName);
                }

                Message message = messageBuilder.build();

                FirebaseMessaging.getInstance().send(message);
            } catch (FirebaseMessagingException e) {
                if (e.getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED) {
                    // Токен невалиден — устройство удалило приложение или токен обновился
                    pushTokenRepository.findByFcmToken(token).ifPresent(pt -> {
                        pushTokenRepository.delete(pt);
                        log.info("Удалён невалидный push-токен для устройства: {}", pt.getDeviceId());
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
        String displayName = userName != null ? userName : "друг";
        String title = "Мы скучаем! ✅";
        String body = displayName + ", ваши списки ждут — загляните!";

        List<String> tokens = pushTokenRepository.findFcmTokensByUserId(userId);
        if (tokens.isEmpty()) {
            log.debug("Нет push-токенов для userId={}, напоминание не отправлено", userId);
            return;
        }

        sendToMultiple(tokens, title, body, null);
        log.info("Push-напоминание отправлено userId={} на {} устройств(а)", userId, tokens.size());
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
