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
