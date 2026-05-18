package ru.mngerasimenko.todolist.service;

/**
 * Сервис отправки push-уведомлений через Firebase Cloud Messaging.
 */
public interface PushNotificationService {

    /**
     * Зарегистрировать/обновить FCM-токен устройства.
     *
     * @param locale язык push-уведомлений на этом устройстве (BCP-47).
     *               Если null/blank — используется fallback "ru" (для совместимости
     *               со старыми Android-клиентами, не поддерживающими per-token locale).
     */
    void registerToken(Long userId, String fcmToken, String deviceId, String locale);

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
     * Отправить напоминание неактивному пользователю (push).
     * @param userId ID пользователя
     * @param userName имя для персонализации
     */
    void sendInactiveReminderPush(Long userId, String userName);

    /**
     * Отправить 3-дневное onboarding-напоминание новому пользователю (push) — Phase 3.3.
     * Текст отличается от inactive-reminder ({@code push.onboarding.*} keys) — фокус на
     * «попробуйте сейчас», а не «возвращайтесь».
     * Payload помечается {@code push_type=onboarding_reminder}.
     *
     * @param userId ID пользователя
     * @param userName имя для персонализации (или null → fallback name из messages)
     */
    void sendOnboardingReminderPush(Long userId, String userName);

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
