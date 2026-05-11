package ru.mngerasimenko.todolist.service;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import ru.mngerasimenko.todolist.settings.EmailProperties;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты для EmailServiceImpl.
 * JavaMailSender / TemplateEngine / MessageService замокированы — тесты проверяют
 * только delivery flow (что mailSender реально вызывается) и graceful обработку ошибок SMTP.
 * Корректность рендеринга Thymeleaf-шаблонов покрывается на уровне самого Thymeleaf.
 */
@ExtendWith(MockitoExtension.class)
class EmailServiceImplTest {

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private SpringTemplateEngine templateEngine;

    @Mock
    private MessageService messageService;

    private EmailProperties emailProperties;
    private EmailServiceImpl emailService;

    @BeforeEach
    void setUp() {
        emailProperties = new EmailProperties();
        emailProperties.setFrom("todo-noreply@keepware.ru");
        emailProperties.setBaseUrl("https://todo.keepware.ru");
        emailProperties.setVerificationTokenTtlHours(24);
        emailProperties.setResetTokenTtlHours(1);
        emailService = new EmailServiceImpl(mailSender, emailProperties, templateEngine, messageService);

        // Loose stubbing — не падаем, если конкретный метод не вызвался в данном тесте.
        lenient().when(templateEngine.process(anyString(), any(Context.class))).thenReturn("<html>rendered</html>");
        lenient().when(messageService.getMessage(anyString(), any(Locale.class))).thenReturn("Subject");
        lenient().when(messageService.getMessage(anyString(), any(Locale.class), any(Object[].class))).thenReturn("Subject");
    }

    @Test
    void sendVerificationEmail_CallsMailSender() {
        MimeMessage mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        emailService.sendVerificationEmail("user@example.com", "test-token-123", "ru");

        verify(mailSender).createMimeMessage();
        verify(mailSender).send(mimeMessage);
        verify(templateEngine).process(eq("email-verification"), any(Context.class));
    }

    @Test
    void sendPasswordResetEmail_CallsMailSender() {
        MimeMessage mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        emailService.sendPasswordResetEmail("user@example.com", "reset-token-456", "ru");

        verify(mailSender).createMimeMessage();
        verify(mailSender).send(mimeMessage);
        verify(templateEngine).process(eq("password-reset"), any(Context.class));
    }

    @Test
    void sendVerificationEmail_DoesNotThrow_WhenMailSenderFails() {
        MimeMessage mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new RuntimeException("SMTP error")).when(mailSender).send(any(MimeMessage.class));

        // Не должен бросать исключение — ошибка логируется
        emailService.sendVerificationEmail("user@example.com", "test-token", "ru");

        verify(mailSender).send(mimeMessage);
    }

    @Test
    void sendPasswordResetEmail_DoesNotThrow_WhenMailSenderFails() {
        MimeMessage mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new RuntimeException("SMTP error")).when(mailSender).send(any(MimeMessage.class));

        emailService.sendPasswordResetEmail("user@example.com", "reset-token", "ru");

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

        emailService.sendInactiveReminderEmail("user@example.com", "Иван", 1L, "ru");

        verify(mailSender).createMimeMessage();
        verify(mailSender).send(mimeMessage);
        verify(templateEngine).process(eq("inactive-reminder"), any(Context.class));
    }

    @Test
    void sendInactiveReminderEmail_NullUserName_LooksUpFallbackInMessageService() {
        MimeMessage mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        emailService.sendInactiveReminderEmail("user@example.com", null, 1L, "ru");

        // При userName=null сервис обязан подтянуть fallback-имя из messages_*.properties,
        // чтобы письмо оставалось локализованным (раньше было hardcoded "друг").
        verify(messageService).getMessage(eq("email.inactive.fallback_name"), any(Locale.class));
        verify(mailSender).send(mimeMessage);
    }
}
