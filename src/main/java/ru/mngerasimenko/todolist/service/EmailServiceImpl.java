package ru.mngerasimenko.todolist.service;

import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.mngerasimenko.todolist.settings.EmailProperties;

import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Реализация сервиса email-рассылки через JavaMailSender.
 * Письма отправляются асинхронно (@Async), чтобы не блокировать основной поток.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;
    private final EmailProperties emailProperties;

    @Override
    @Async
    public void sendVerificationEmail(String email, String token) {
        String link = emailProperties.getBaseUrl() + "/verify-email?token=" + token;
        int ttlHours = emailProperties.getVerificationTokenTtlHours();
        String html = loadTemplate("templates/email-verification.html")
                .replace("{{link}}", link)
                .replace("{{ttlHours}}", String.valueOf(ttlHours));
        sendHtmlEmail(email, "Подтвердите email — Список задач", html);
    }

    @Override
    @Async
    public void sendPasswordResetEmail(String email, String token) {
        String link = emailProperties.getBaseUrl() + "/reset-password?token=" + token;
        int ttlHours = emailProperties.getResetTokenTtlHours();
        String html = loadTemplate("templates/password-reset.html")
                .replace("{{link}}", link)
                .replace("{{ttlHours}}", String.valueOf(ttlHours));
        sendHtmlEmail(email, "Сброс пароля — Список задач", html);
    }

    /**
     * Отправка HTML-письма.
     */
    private void sendHtmlEmail(String to, String subject, String htmlBody) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(emailProperties.getFrom());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(message);
            log.info("Email отправлен на {}: {}", to, subject);
        } catch (Exception e) {
            log.error("Ошибка отправки email на {}: {}", to, e.getMessage());
        }
    }

    @Override
    @Async
    public void sendInviteEmail(String email, String inviteLink, String listName, String inviterName) {
        int ttlHours = emailProperties.getInviteTokenTtlHours();
        String safeListName = HtmlUtils.htmlEscape(listName);
        String safeInviterName = HtmlUtils.htmlEscape(inviterName);
        String html = loadTemplate("templates/invite.html")
                .replace("{{link}}", inviteLink)
                .replace("{{listName}}", safeListName)
                .replace("{{inviterName}}", safeInviterName)
                .replace("{{ttlHours}}", String.valueOf(ttlHours));
        sendHtmlEmail(email, "Приглашение в список «" + safeListName + "» — Список задач", html);
    }

    @Override
    public boolean isSmtpHealthy() {
        try {
            if (mailSender instanceof JavaMailSenderImpl impl) {
                Transport transport = impl.getSession().getTransport("smtps");
                transport.connect(impl.getHost(), impl.getPort(),
                        impl.getUsername(), impl.getPassword());
                boolean connected = transport.isConnected();
                transport.close();
                return connected;
            }
            return false;
        } catch (Exception e) {
            log.warn("SMTP health check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Загрузка HTML-шаблона из classpath.
     */
    private String loadTemplate(String path) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException("Шаблон не найден: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Ошибка чтения шаблона: " + path, e);
        }
    }
}
