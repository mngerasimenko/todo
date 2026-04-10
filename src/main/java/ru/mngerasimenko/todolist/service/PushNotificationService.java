package ru.mngerasimenko.todolist.service;

/**
 * Сервис отправки push-уведомлений через Firebase Cloud Messaging.
 */
public interface PushNotificationService {

    /**
     * Зарегистрировать/обновить FCM-токен устройства.
     */
    void registerToken(Long userId, String fcmToken, String deviceId);

    /**
     * Удалить токен устройства (при logout). Только владелец может удалить свой токен.
     */
    void removeToken(Long userId, String deviceId);

    /**
     * Уведомить участников списка о новой задаче.
     */
    void notifyNewTodo(Long listId, Long authorUserId, String authorName, String todoName);

    /**
     * Уведомить участников списка о выполнении задачи (кроме того, кто выполнил).
     */
    void notifyTodoCompleted(Long completorUserId, Long listId, String completorName, String todoName);

    /**
     * Уведомить участников списка о новом участнике.
     */
    void notifyNewMember(Long listId, Long newUserId, String newUserName, String listName);

    /**
     * Проверить доступность Firebase (кешированный результат).
     * @return true если Firebase SDK инициализирован и работает
     */
    boolean isFirebaseHealthy();

    /**
     * Выполнить проверку здоровья Firebase и обновить кеш.
     */
    void checkFirebaseHealth();
}
