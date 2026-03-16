package ru.mngerasimenko.todolist.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import ru.mngerasimenko.todolist.settings.EmailProperties;

import java.util.Properties;
import java.util.UUID;

/**
 * Отправка реальных тестовых писем через SMTP.
 * Запуск: MAIL_USERNAME=... MAIL_PASSWORD=... mvn test -Psmtp -Dtest=SmtpSendTest -Djacoco.skip=true
 */
@Tag("smtp")
class SmtpSendTest {

    @Test
    void sendTestVerificationEmail() {
        EmailServiceImpl emailService = createEmailService();
        String token = UUID.randomUUID().toString();
        emailService.sendVerificationEmail("mngerasimenko@gmail.com", token);
    }

    @Test
    void sendTestPasswordResetEmail() {
        EmailServiceImpl emailService = createEmailService();
        String token = UUID.randomUUID().toString();
        emailService.sendPasswordResetEmail("mngeras@yandex.ru", token);
    }

    private EmailServiceImpl createEmailService() {
        String username = System.getenv("MAIL_USERNAME");
        String password = System.getenv("MAIL_PASSWORD");

        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost("mail.hosting.reg.ru");
        mailSender.setPort(465);
        mailSender.setUsername(username);
        mailSender.setPassword(password);

        Properties props = mailSender.getJavaMailProperties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.ssl.enable", "true");
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");

        EmailProperties emailProperties = new EmailProperties();
        emailProperties.setFrom("todo-noreply@mngerasimenko.ru");
        emailProperties.setBaseUrl("https://todo.mngerasimenko.ru");
        emailProperties.setVerificationTokenTtlHours(24);
        emailProperties.setResetTokenTtlHours(1);

        return new EmailServiceImpl(mailSender, emailProperties);
    }
}
