package ru.mngerasimenko.todolist.service;

/**
 * Сервис отправки email-уведомлений.
 */
public interface EmailService {

    /**
     * Отправить письмо для подтверждения email.
     *
     * @param locale язык письма (BCP-47, e.g. "ru", "en"). Если null/blank — fallback "ru".
     *               Обычно передаётся {@code User.preferredEmailLocale}.
     */
    void sendVerificationEmail(String email, String token, String locale);

    /**
     * Отправить письмо для сброса пароля.
     *
     * @param locale язык письма (BCP-47). Если null/blank — fallback "ru".
     */
    void sendPasswordResetEmail(String email, String token, String locale);

    /**
     * Отправить письмо с приглашением в список задач.
     *
     * @param locale язык письма (BCP-47). Обычно передаётся локаль приглашающего
     *               ({@code inviter.preferredEmailLocale}). Если null/blank — fallback "ru".
     */
    void sendInviteEmail(String email, String inviteLink, String listName, String inviterName, String locale);

    /**
     * Отправить напоминание неактивному пользователю.
     * @param email email пользователя
     * @param userName имя пользователя для персонализации
     * @param userId ID пользователя для трекинга
     * @param locale язык письма (BCP-47). Обычно передаётся {@code user.preferredEmailLocale}.
     *               Если null/blank — fallback "ru".
     */
    void sendInactiveReminderEmail(String email, String userName, Long userId, String locale);

    /**
     * То же, что {@link #sendInactiveReminderEmail(String, String, Long, String)}, плюс
     * одноразовый unsubscribe-токен для footer-link. Если token null/blank — footer
     * отписки не отображается (поведение совместимо со старой сигнатурой).
     */
    void sendInactiveReminderEmail(String email, String userName, Long userId, String locale, String unsubscribeToken);

    /**
     * Отправить 3-дневное onboarding-напоминание новому пользователю (Phase 3.3).
     * Текст отличается от inactive-reminder ({@code email.onboarding.*} keys) — фокус
     * на «попробуйте сейчас», а не «возвращайтесь».
     *
     * @param email email пользователя
     * @param userName имя для персонализации (null → fallback из messages)
     * @param userId ID для трекинга
     * @param locale BCP-47 локаль (обычно {@code user.preferredEmailLocale})
     * @param unsubscribeToken одноразовый токен для footer-link (null → footer не показывается)
     */
    void sendOnboardingReminderEmail(String email, String userName, Long userId, String locale, String unsubscribeToken);

    /**
     * Отправить напоминание о сроке собственной задачи пользователя (Task 7).
     * Отдельный канал согласия от {@link #sendInactiveReminderEmail}: unsubscribe-ссылка несёт
     * {@code scope=todo_due} и выключает {@code User.todoReminderEmailEnabled}, а не
     * {@code reminder_opt_out}.
     *
     * @param email email пользователя
     * @param userName имя пользователя для персонализации (null → fallback из messages)
     * @param todoName название задачи
     * @param listName название списка, в котором находится задача
     * @param dueAt дата и время срока в формате {@code dd.MM.yyyy HH:mm} — без этого письмо
     *              не отличить "срок сегодня" от "срок через неделю" при большом remind_before_minutes
     * @param userId ID пользователя (не используется для трекинга кликов — только для пикселя open)
     * @param locale язык письма (BCP-47). Обычно передаётся {@code user.preferredEmailLocale}.
     *               Если null/blank — fallback "ru".
     * @param unsubscribeToken одноразовый токен для footer-link (null → footer не показывается)
     */
    void sendTodoDueEmail(String email, String userName, String todoName, String listName, String dueAt,
                           Long userId, String locale, String unsubscribeToken);

    /**
     * Проверить доступность SMTP-сервера (кешированный результат).
     * @return true если последняя проверка была успешной
     */
    boolean isSmtpHealthy();

    /**
     * Выполнить SMTP health check и обновить кеш.
     * Вызывается из SmtpHealthScheduler.
     */
    void checkSmtpHealth();
}
