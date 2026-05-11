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
import org.springframework.web.util.HtmlUtils;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import ru.mngerasimenko.todolist.settings.EmailProperties;

import java.util.Locale;
import java.util.Map;

import static ru.mngerasimenko.todolist.util.LogUtils.maskEmail;

/**
 * Реализация сервиса email-рассылки через JavaMailSender.
 * Письма отправляются асинхронно (@Async), чтобы не блокировать основной поток.
 * <p>
 * Шаблоны рендерятся через Thymeleaf {@link SpringTemplateEngine}, локализованные строки
 * подтягиваются из {@link MessageService} (бандлы {@code messages_ru.properties},
 * {@code messages_en.properties}). Локаль пока hardcoded {@code ru} — после Server R-3 (B.6)
 * будет подтягиваться из {@code User.preferredEmailLocale}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailServiceImpl implements EmailService {

    /**
     * Fallback-локаль для писем — используется если caller передал null/blank.
     */
    private static final String DEFAULT_LOCALE_TAG = "ru";

    private final JavaMailSender mailSender;
    private final EmailProperties emailProperties;
    private final SpringTemplateEngine templateEngine;
    private final MessageService messageService;

    /** Кешированный результат SMTP health check (обновляется раз в 15 минут через SmtpHealthScheduler) */
    private volatile boolean smtpHealthyCache = false;

    @Override
    @Async
    public void sendVerificationEmail(String email, String token, String localeTag) {
        Locale locale = resolveLocale(localeTag);
        String link = emailProperties.getBaseUrl() + "/verify-email?token=" + token;
        int ttlHours = emailProperties.getVerificationTokenTtlHours();
        String html = renderTemplate("email-verification", locale, Map.of(
                "link", link,
                "ttlHours", ttlHours
        ));
        sendHtmlEmail(email, messageService.getMessage("email.verify.subject", locale), html);
    }

    @Override
    @Async
    public void sendPasswordResetEmail(String email, String token, String localeTag) {
        Locale locale = resolveLocale(localeTag);
        String link = emailProperties.getBaseUrl() + "/reset-password?token=" + token;
        int ttlHours = emailProperties.getResetTokenTtlHours();
        String html = renderTemplate("password-reset", locale, Map.of(
                "link", link,
                "ttlHours", ttlHours
        ));
        sendHtmlEmail(email, messageService.getMessage("email.reset.subject", locale), html);
    }

    @Override
    @Async
    public void sendInviteEmail(String email, String inviteLink, String listName, String inviterName, String localeTag) {
        Locale locale = resolveLocale(localeTag);
        int ttlHours = emailProperties.getInviteTokenTtlHours();
        String safeListName = HtmlUtils.htmlEscape(listName);
        String safeInviterName = HtmlUtils.htmlEscape(inviterName);
        String html = renderTemplate("invite", locale, Map.of(
                "link", inviteLink,
                "listName", safeListName,
                "inviterName", safeInviterName,
                "ttlHours", ttlHours
        ));
        // Subject содержит имя списка — экранирование не нужно (текст, не HTML)
        String subject = messageService.getMessage("email.invite.subject", locale, listName);
        sendHtmlEmail(email, subject, html);
    }

    @Override
    @Async
    public void sendInactiveReminderEmail(String email, String userName, Long userId, String localeTag) {
        Locale locale = resolveLocale(localeTag);
        String fallback = messageService.getMessage("email.inactive.fallback_name", locale);
        String safeName = HtmlUtils.htmlEscape(userName != null ? userName : fallback);
        String baseUrl = emailProperties.getBaseUrl();
        String rustoreLink = "https://www.rustore.ru/catalog/app/ru.mngerasimenko.todolist";
        String trackClickLink = baseUrl + "/api/track/click/" + userId;
        String trackOpenLink = baseUrl + "/api/track/open/" + userId;
        String html = renderTemplate("inactive-reminder", locale, Map.of(
                "userName", safeName,
                "rustoreLink", rustoreLink,
                "trackClickLink", trackClickLink,
                "trackOpenLink", trackOpenLink
        ));
        sendHtmlEmail(email, messageService.getMessage("email.inactive.subject", locale), html);
    }

    /**
     * Резолв локали из BCP-47 строки. Null/blank → "ru". Невалидный тэг (e.g. "*")
     * остаётся как есть — MessageSource сам сделает fallback на default.
     */
    private Locale resolveLocale(String localeTag) {
        if (localeTag == null || localeTag.isBlank()) {
            return Locale.forLanguageTag(DEFAULT_LOCALE_TAG);
        }
        return Locale.forLanguageTag(localeTag);
    }

    @Override
    public boolean isSmtpHealthy() {
        return smtpHealthyCache;
    }

    /**
     * Выполняет SMTP health check и обновляет кеш.
     * Вызывается из SmtpHealthScheduler (раз в 15 минут).
     */
    public void checkSmtpHealth() {
        try {
            if (mailSender instanceof JavaMailSenderImpl impl) {
                Transport transport = impl.getSession().getTransport("smtps");
                transport.connect(impl.getHost(), impl.getPort(),
                        impl.getUsername(), impl.getPassword());
                boolean connected = transport.isConnected();
                transport.close();
                smtpHealthyCache = connected;
            }
        } catch (Exception e) {
            smtpHealthyCache = false;
            log.warn("SMTP health check failed: {}", e.getMessage());
        }
    }

    /**
     * Рендеринг Thymeleaf-шаблона с переданной локалью и переменными.
     */
    private String renderTemplate(String templateName, Locale locale, Map<String, Object> variables) {
        Context context = new Context(locale);
        variables.forEach(context::setVariable);
        return templateEngine.process(templateName, context);
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
            log.info("Email отправлен на {}: {}", maskEmail(to), subject);
        } catch (Exception e) {
            log.error("Ошибка отправки email на {}: {}", maskEmail(to), e.getMessage());
        }
    }
}
