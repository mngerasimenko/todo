package ru.mngerasimenko.todolist.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.templatemode.TemplateMode;
import ru.mngerasimenko.todolist.settings.EmailProperties;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
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
        emailService.sendVerificationEmail("mngerasimenko@gmail.com", token, "ru");
    }

    @Test
    void sendTestPasswordResetEmail() {
        EmailServiceImpl emailService = createEmailService();
        String token = UUID.randomUUID().toString();
        emailService.sendPasswordResetEmail("mngeras@yandex.ru", token, "ru");
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
        emailProperties.setFrom("todo-noreply@keepware.ru");
        emailProperties.setBaseUrl("https://todo.keepware.ru");
        emailProperties.setVerificationTokenTtlHours(24);
        emailProperties.setResetTokenTtlHours(1);

        // MessageSource — те же бандлы, что в проде
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding(StandardCharsets.UTF_8.name());
        messageSource.setDefaultLocale(Locale.forLanguageTag("ru"));
        messageSource.setFallbackToSystemLocale(false);

        // Thymeleaf engine — конфигурация совпадает с продовым autoconfig (см. application.properties)
        SpringResourceTemplateResolver templateResolver = new SpringResourceTemplateResolver();
        templateResolver.setPrefix("classpath:/templates/");
        templateResolver.setSuffix(".html");
        templateResolver.setTemplateMode(TemplateMode.HTML);
        templateResolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        templateResolver.setCacheable(true);

        SpringTemplateEngine templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(templateResolver);
        templateEngine.setTemplateEngineMessageSource(messageSource);

        MessageService messageService = new MessageService(messageSource);

        return new EmailServiceImpl(mailSender, emailProperties, templateEngine, messageService);
    }
}
