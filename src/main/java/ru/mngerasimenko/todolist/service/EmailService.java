package ru.mngerasimenko.todolist.service;

/**
 * Сервис отправки email-уведомлений.
 */
public interface EmailService {

    /**
     * Отправить письмо для подтверждения email.
     */
    void sendVerificationEmail(String email, String token);

    /**
     * Отправить письмо для сброса пароля.
     */
    void sendPasswordResetEmail(String email, String token);

    /**
     * Отправить письмо с приглашением в список задач.
     */
    void sendInviteEmail(String email, String inviteLink, String listName, String inviterName);

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
