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
     * Тихо сообщить участникам списка, что задача изменилась.
     * <p>
     * Уведомление НЕ показывается: в сообщении нет notification-payload, только data. Клиент
     * на такой push ПЫТАЕТСЯ перечитать список — этот путь есть в Android-клиенте начиная
     * с 1.1.7 (vc13), поэтому отдельной клиентской правки не требуется, чтобы ничего не
     * сломалось. Но эффект не гарантирован: обновление запускается незавершаемой корутиной
     * уже после возврата из {@code onMessageReceived}, а сервис к этому моменту отпускает
     * wakelock — при закрытом приложении работа может не доработать. Настоящая гарантия
     * требует клиентской правки (WorkManager вместо fire-and-forget).
     * <p>
     * Редактор из получателей НЕ исключается, в отличие от видимых уведомлений: сообщение
     * ничего не показывает, а исключение шло бы по пользователю и отсекло бы второе
     * устройство самого редактора. {@code editorUserId} нужен только для диагностики.
     * <p>
     * Нужно потому, что push'и есть на создание задачи и на её выполнение, а на правку —
     * не было ни одного: у участника оставалась строка в состоянии «до правки», и увидеть
     * актуальную он мог, только открыв список заново (баг с прода 08.09.2026).
     * <p>
     * Закрывает именно правку через {@code PUT /api/todos/{id}}. Снятие галочки
     * ({@code PATCH .../undone}) и удаление задачи по-прежнему не рассылаются — оба клиента
     * ходят туда отдельными эндпоинтами, и там тот же класс бага остаётся.
     */
    void notifyTodoUpdated(Long listId, Long editorUserId, Long todoId);

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
     * Отправить push-напоминание о наступившем сроке задачи (due date reminder).
     * Payload помечается {@code push_type=todo_due} и несёт {@code todo_id} (для будущего экрана
     * задачи) плюс {@code list_id}/{@code list_name} — те же ключи, что у остальных пяти push-типов,
     * которые Android уже читает для deep link в список ({@code TodoFirebaseMessagingService.kt}).
     *
     * @param userId ID пользователя — получателя напоминания
     * @param todoId ID задачи, у которой наступил срок
     * @param listId ID списка, которому принадлежит задача (для deep link)
     * @param todoName название задачи для текста push
     * @param dueAt дата и время срока в формате {@code dd.MM.yyyy HH:mm} — без этого текст push
     *              не отличить "срок сегодня" от "срок через неделю" при большом remind_before_minutes
     */
    void sendTodoDuePush(Long userId, Long todoId, Long listId, String todoName, String dueAt);

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
