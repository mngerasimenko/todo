package ru.mngerasimenko.todolist.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.templatemode.TemplateMode;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration-тест рендеринга email-шаблонов через настоящий Thymeleaf SpringTemplateEngine.
 * <p>
 * Покрывает то, чего не ловит юнит-тест EmailServiceImplTest (там TemplateEngine замокирован):
 * опечатки в именах ключей в шаблонах и в .properties, опечатки в именах шаблонов,
 * целостность подстановки переменных, корректную работу MessageFormat при i18n и th:utext+HTML.
 * <p>
 * Использует minimal Spring context (только Thymeleaf + MessageSource) — не поднимает полное приложение.
 */
@SpringJUnitConfig(EmailTemplateRenderingTest.TestConfig.class)
class EmailTemplateRenderingTest {

    private static final Locale RU = Locale.forLanguageTag("ru");
    private static final Locale EN = Locale.forLanguageTag("en");

    @Configuration
    static class TestConfig {
        @Bean
        public MessageSource messageSource() {
            ResourceBundleMessageSource source = new ResourceBundleMessageSource();
            source.setBasename("messages");
            source.setDefaultEncoding(StandardCharsets.UTF_8.name());
            source.setDefaultLocale(RU);
            source.setFallbackToSystemLocale(false);
            return source;
        }

        @Bean
        public SpringResourceTemplateResolver templateResolver() {
            SpringResourceTemplateResolver resolver = new SpringResourceTemplateResolver();
            resolver.setPrefix("classpath:/templates/");
            resolver.setSuffix(".html");
            resolver.setTemplateMode(TemplateMode.HTML);
            resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
            // Cache enabled — matches production (spring.thymeleaf.cache=true) and SmtpSendTest;
            // each test renders a unique (template, locale) pair so caching has no observable effect.
            resolver.setCacheable(true);
            return resolver;
        }

        @Bean
        public SpringTemplateEngine templateEngine(SpringResourceTemplateResolver templateResolver,
                                                    MessageSource messageSource) {
            SpringTemplateEngine engine = new SpringTemplateEngine();
            engine.setTemplateResolver(templateResolver);
            engine.setTemplateEngineMessageSource(messageSource);
            return engine;
        }
    }

    @Autowired
    private SpringTemplateEngine templateEngine;

    private String render(String templateName, Locale locale, Map<String, Object> vars) {
        Context ctx = new Context(locale);
        vars.forEach(ctx::setVariable);
        return templateEngine.process(templateName, ctx);
    }

    // === email-verification ===

    @Test
    void verifyEmail_RendersRussianContent() {
        String html = render("email-verification", RU, Map.of(
                "link", "https://example.test/verify-email?token=abc123",
                "ttlHours", 24
        ));

        assertThat(html)
                .contains("lang=\"ru\"")
                .contains("Наш список")
                .contains("Подтвердите ваш email")
                .contains("Подтвердить email")
                .contains("https://example.test/verify-email?token=abc123")
                .contains("Если кнопка не работает")
                .contains("24 ч.");
    }

    @Test
    void verifyEmail_RendersEnglishContent() {
        String html = render("email-verification", EN, Map.of(
                "link", "https://example.test/verify-email?token=abc123",
                "ttlHours", 24
        ));

        assertThat(html)
                .contains("lang=\"en\"")
                .contains("TodoList")
                .contains("Confirm your email")
                .contains("Confirm email")
                .contains("https://example.test/verify-email?token=abc123")
                .contains("If the button doesn")
                .contains("24 hour");
    }

    // === password-reset ===

    @Test
    void passwordReset_RendersRussianContent() {
        String html = render("password-reset", RU, Map.of(
                "link", "https://example.test/reset?token=xyz",
                "ttlHours", 1
        ));

        assertThat(html)
                .contains("lang=\"ru\"")
                .contains("Сброс пароля")
                .contains("Сбросить пароль")
                .contains("https://example.test/reset?token=xyz")
                .contains("1 ч.");
    }

    @Test
    void passwordReset_RendersEnglishContent() {
        String html = render("password-reset", EN, Map.of(
                "link", "https://example.test/reset?token=xyz",
                "ttlHours", 1
        ));

        assertThat(html)
                .contains("lang=\"en\"")
                .contains("Password reset")
                .contains("Reset password")
                .contains("https://example.test/reset?token=xyz")
                .contains("1 hour");
    }

    // === invite ===

    @Test
    void invite_RendersRussianWithStrongTagsFromUtext() {
        // inviterName и listName экранируются в EmailServiceImpl; здесь передаём «безопасные» значения,
        // чтобы убедиться, что <strong> из messages_ru.properties реально рендерится через th:utext.
        String html = render("invite", RU, Map.of(
                "link", "https://example.test/invite/abc",
                "listName", "Покупки",
                "inviterName", "Иван",
                "ttlHours", 24
        ));

        assertThat(html)
                .contains("lang=\"ru\"")
                .contains("Приглашение в список")
                .contains("<strong>Иван</strong>")
                .contains("<strong>«Покупки»</strong>")
                .contains("Принять приглашение")
                .contains("https://example.test/invite/abc")
                .contains("24 ч.");
    }

    @Test
    void invite_RendersEnglishWithStrongTagsFromUtext() {
        String html = render("invite", EN, Map.of(
                "link", "https://example.test/invite/abc",
                "listName", "Shopping",
                "inviterName", "John",
                "ttlHours", 24
        ));

        assertThat(html)
                .contains("lang=\"en\"")
                .contains("List invitation")
                .contains("<strong>John</strong>")
                .contains("<strong>\"Shopping\"</strong>")
                .contains("Accept invitation")
                .contains("https://example.test/invite/abc")
                .contains("24 hour");
    }

    // === inactive-reminder ===

    @Test
    void inactiveReminder_RendersRussianContent() {
        String html = render("inactive-reminder", RU, Map.of(
                "userName", "Анна",
                "rustoreLink", "https://rustore.example/app",
                "trackClickLink", "https://track.example/click/1",
                "trackOpenLink", "https://track.example/open/1"
        ));

        assertThat(html)
                .contains("lang=\"ru\"")
                .contains("Наш список")
                .contains("Мы скучаем!")
                .contains("Привет, Анна!")
                .contains("Открыть приложение")
                .contains("https://track.example/click/1")
                .contains("https://track.example/open/1")
                .contains("https://rustore.example/app");
    }

    @Test
    void inactiveReminder_RendersEnglishContent() {
        String html = render("inactive-reminder", EN, Map.of(
                "userName", "Anna",
                "rustoreLink", "https://rustore.example/app",
                "trackClickLink", "https://track.example/click/1",
                "trackOpenLink", "https://track.example/open/1"
        ));

        assertThat(html)
                .contains("lang=\"en\"")
                .contains("TodoList")
                .contains("We miss you!")
                .contains("Hi, Anna!")
                .contains("Open the app")
                .contains("https://track.example/click/1")
                .contains("https://track.example/open/1");
    }

    // === todo-reminder (Task 7) ===

    @Test
    void todoReminderTemplate_RendersTaskListAndUnsubscribe() {
        String html = render("todo-reminder", RU, Map.of(
                "userName", "Мария",
                "todoName", "Полить теплицу",
                "listName", "Дача",
                "dueAt", "31.07.2026 18:00",
                "listUrl", "https://todo.keepware.ru",
                "trackOpenLink", "https://track.example/open/1",
                "unsubscribeUrl", "https://todo.keepware.ru/api/users/unsubscribe-reminder?token=abc&scope=todo_due"
        ));

        assertThat(html)
                .contains("Напоминание о задаче")
                .contains("Полить теплицу")
                .contains("Дача")
                .contains("31.07.2026 18:00")
                .contains("scope=todo_due");
    }

    @Test
    void todoReminderTemplate_RendersEnglishContent() {
        String html = render("todo-reminder", EN, Map.of(
                "userName", "Anna",
                "todoName", "Water the plants",
                "listName", "Garden",
                "dueAt", "31.07.2026 18:00",
                "listUrl", "https://todo.keepware.ru",
                "trackOpenLink", "https://track.example/open/1",
                "unsubscribeUrl", "https://todo.keepware.ru/api/users/unsubscribe-reminder?token=abc&scope=todo_due"
        ));

        assertThat(html)
                .contains("lang=\"en\"")
                .contains("TodoList")
                .contains("Task reminder")
                .contains("Hi, Anna!")
                .contains("Water the plants")
                .contains("Garden")
                .contains("due 31.07.2026 18:00")
                .contains("Open the list →")
                .contains("This is an automated reminder from TodoList")
                .contains("Turn off task due-date emails")
                .contains("scope=todo_due");
    }
}
