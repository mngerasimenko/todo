package ru.mngerasimenko.todolist.service;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import ru.mngerasimenko.todolist.settings.EmailProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты для EmailServiceImpl.
 * Проверяют формирование и отправку писем (JavaMailSender замокирован).
 */
@ExtendWith(MockitoExtension.class)
class EmailServiceImplTest {

    @Mock
    private JavaMailSender mailSender;

    private EmailProperties emailProperties;
    private EmailServiceImpl emailService;

    @BeforeEach
    void setUp() {
        emailProperties = new EmailProperties();
        emailProperties.setFrom("todo-noreply@keepware.ru");
        emailProperties.setBaseUrl("https://todo.keepware.ru");
        emailProperties.setVerificationTokenTtlHours(24);
        emailProperties.setResetTokenTtlHours(1);
        emailService = new EmailServiceImpl(mailSender, emailProperties);
    }

    @Test
    void sendVerificationEmail_CallsMailSender() {
        MimeMessage mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        emailService.sendVerificationEmail("user@example.com", "test-token-123");

        verify(mailSender).createMimeMessage();
        verify(mailSender).send(mimeMessage);
    }

    @Test
    void sendPasswordResetEmail_CallsMailSender() {
        MimeMessage mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        emailService.sendPasswordResetEmail("user@example.com", "reset-token-456");

        verify(mailSender).createMimeMessage();
        verify(mailSender).send(mimeMessage);
    }

    @Test
    void sendVerificationEmail_DoesNotThrow_WhenMailSenderFails() {
        MimeMessage mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new RuntimeException("SMTP error")).when(mailSender).send(any(MimeMessage.class));

        // Не должен бросать исключение — ошибка логируется
        emailService.sendVerificationEmail("user@example.com", "test-token");

        verify(mailSender).send(mimeMessage);
    }

    @Test
    void sendPasswordResetEmail_DoesNotThrow_WhenMailSenderFails() {
        MimeMessage mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new RuntimeException("SMTP error")).when(mailSender).send(any(MimeMessage.class));

        emailService.sendPasswordResetEmail("user@example.com", "reset-token");

        verify(mailSender).send(mimeMessage);
    }

    @Test
    void isSmtpHealthy_NonJavaMailSenderImpl_ReturnsFalse() {
        // mailSender — мок (не JavaMailSenderImpl), поэтому должен вернуть false
        boolean result = emailService.isSmtpHealthy();

        assertThat(result).isFalse();
    }

    @Test
    void sendInactiveReminderEmail_CallsMailSender() {
        MimeMessage mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        emailService.sendInactiveReminderEmail("user@example.com", "Иван");

        verify(mailSender).createMimeMessage();
        verify(mailSender).send(mimeMessage);
    }
}
