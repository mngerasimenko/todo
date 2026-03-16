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
     * Проверить доступность SMTP-сервера.
     * @return true если подключение и аутентификация успешны
     */
    boolean isSmtpHealthy();
}
