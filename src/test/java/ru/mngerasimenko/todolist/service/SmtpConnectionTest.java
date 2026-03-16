package ru.mngerasimenko.todolist.service;

import jakarta.mail.Session;
import jakarta.mail.Transport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Интеграционный тест SMTP-подключения к mail.hosting.reg.ru.
 * Запуск вручную: mvn test -Dtest=SmtpConnectionTest -Dgroups=smtp -Djacoco.skip=true
 * Требует переменные окружения: MAIL_USERNAME, MAIL_PASSWORD
 */
@Tag("smtp")
class SmtpConnectionTest {

    @Test
    void smtpConnection_SuccessfulAuth() {
        String username = System.getenv("MAIL_USERNAME");
        String password = System.getenv("MAIL_PASSWORD");

        assertThat(username).as("MAIL_USERNAME не задан").isNotBlank();
        assertThat(password).as("MAIL_PASSWORD не задан").isNotBlank();

        Properties props = new Properties();
        props.put("mail.smtp.host", "mail.hosting.reg.ru");
        props.put("mail.smtp.port", "465");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.ssl.enable", "true");
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");

        Session session = Session.getInstance(props);

        assertThatCode(() -> {
            Transport transport = session.getTransport("smtps");
            transport.connect("mail.hosting.reg.ru", 465, username, password);
            assertThat(transport.isConnected()).isTrue();
            transport.close();
        }).doesNotThrowAnyException();
    }
}
