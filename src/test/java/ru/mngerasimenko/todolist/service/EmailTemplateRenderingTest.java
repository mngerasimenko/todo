package ru.mngerasimenko.todolist.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

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

    /** Так Thymeleaf печатает ключ, которого нет в бандле: {@code ??email.foo_ru??}. */
    private static final Pattern UNRESOLVED_MESSAGE_KEY = Pattern.compile("\\?\\?[\\w.]+_\\w+\\?\\?");

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
            // the cache holds the parsed template, not the rendered output, so tests that render
            // the same template with different variables stay independent.
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
        String html = templateEngine.process(templateName, ctx);
        // Неразрешённый ключ Thymeleaf не роняет рендеринг, а печатает ??key_locale?? прямо в письмо.
        // Без этой проверки опечатка проходит мимо файла для любого ключа, чей текст нигде
        // не проверяется contains-ассертом (email.inactive.image_alt, email.inactive.rustore_prefix).
        assertThat(UNRESOLVED_MESSAGE_KEY.matcher(html).results().map(MatchResult::group).toList())
                .as("неразрешённые message-ключи: шаблон=%s, locale=%s", templateName, locale)
                .isEmpty();
        return html;
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
                .contains("href=\"https://todo.keepware.ru/api/users/unsubscribe-reminder?token=abc&amp;scope=todo_due\"")
                .contains("Отключить письма о сроках задач");
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

    // === onboarding-reminder (3-дневное письмо новым пользователям, Phase 3.3) ===

    @Test
    void onboardingReminder_RendersRussianContent() {
        String html = render("onboarding-reminder", RU, reminderVars("onboarding-reminder", "Анна",
                "https://todo.keepware.ru/api/users/unsubscribe-reminder?token=abc123"));

        assertThat(html)
                .contains("lang=\"ru\"")
                .contains(">Готовы попробовать? — Наш список</title>")  // email.onboarding.subject
                .contains(">Готовы попробовать?</p>")                  // email.onboarding.banner
                .contains("Привет, Анна!")
                .contains("Несколько дней назад вы зарегистрировались")
                .contains("Создать список →")
                .contains("https://track.example/click/1")
                .contains("https://track.example/open/1")
                .contains("Это автоматическое напоминание")
                .contains("href=\"https://todo.keepware.ru/api/users/unsubscribe-reminder?token=abc123\"")
                .contains("Отписаться от напоминаний");
    }

    @Test
    void onboardingReminder_RendersEnglishContent() {
        String html = render("onboarding-reminder", EN, reminderVars("onboarding-reminder", "Anna",
                "https://todo.keepware.ru/api/users/unsubscribe-reminder?token=abc123"));

        assertThat(html)
                .contains("lang=\"en\"")
                .contains(">Ready to start? — TodoList</title>")  // email.onboarding.subject
                .contains(">Ready to start?</p>")                // email.onboarding.banner
                .contains("Hi, Anna!")
                .contains("A few days ago you signed up")
                .contains("Create a list →")
                .contains("https://track.example/click/1")
                .contains("https://track.example/open/1")
                .contains("This is an automated reminder from TodoList")
                .contains("href=\"https://todo.keepware.ru/api/users/unsubscribe-reminder?token=abc123\"")
                .contains("Unsubscribe from reminders");
    }

    // === footer-link отписки (общий блок reminder-писем) ===

    @Test
    void inactiveReminder_RendersUnsubscribeFooterLink() {
        String html = render("inactive-reminder", RU, reminderVars("inactive-reminder", "Анна",
                "https://todo.keepware.ru/api/users/unsubscribe-reminder?token=xyz789"));

        assertThat(html)
                .contains("href=\"https://todo.keepware.ru/api/users/unsubscribe-reminder?token=xyz789\"")
                .contains("Отписаться от напоминаний");
    }

    @ParameterizedTest
    @CsvSource({
            "onboarding-reminder, Отписаться от напоминаний",
            "inactive-reminder,   Отписаться от напоминаний",
            "todo-reminder,       Отключить письма о сроках задач"
    })
    void reminderTemplates_OmitUnsubscribeFooterWhenUrlIsNull(String template, String footerLabel) {
        // EmailServiceImpl.buildUnsubscribeUrl() возвращает null, если токен не сгенерирован, —
        // тогда th:if="${unsubscribeUrl}" не должен отрендерить footer-link ни в одном из писем.
        String html = render(template, RU, reminderVars(template, "Анна", null));

        assertThat(html)
                .contains("Это автоматическое напоминание")  // footer-блок письма на месте...
                .doesNotContain(footerLabel);                // ...но без ссылки отписки
    }

    @ParameterizedTest
    @ValueSource(strings = {"onboarding-reminder", "inactive-reminder", "todo-reminder"})
    void reminderTemplates_EscapeUserNameInGreeting(String template) {
        // Проверяется шаблонный слой: приветствие во всех reminder-письмах идёт через th:text,
        // поэтому сырое имя обязано экранироваться самим шаблоном. EmailServiceImpl вдобавок
        // прогоняет имя через HtmlUtils.htmlEscape до подстановки — вместе это даёт двойное
        // экранирование: в письмо уходит «Ann &amp;amp; Kate», получатель видит буквальное
        // «Ann &amp; Kate» вместо «Ann & Kate». Это дефект сервиса, не шаблона.
        String html = render(template, RU, reminderVars(template, "<script>alert('xss')</script>", null));

        assertThat(html)
                .doesNotContain("<script>")
                .contains("&lt;script&gt;alert(");
    }

    /**
     * Набор переменных reminder-шаблона — ручное зеркало того, что кладёт EmailServiceImpl.
     * {@code unsubscribeUrl} может быть null: так сервис сигнализирует, что unsubscribe-токен
     * не сгенерирован и footer-link рендерить не нужно.
     * <p>
     * Совпадение с сервисом ничем не проверяется — этот тест сам подставляет переменные, поэтому
     * потерянный в EmailServiceImpl {@code vars.put(...)} он не поймает; такой assert — за
     * captor'ом Context в EmailServiceImplTest.
     */
    private static Map<String, Object> reminderVars(String template, String userName, String unsubscribeUrl) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("userName", userName);
        vars.put("trackOpenLink", "https://track.example/open/1");
        vars.put("unsubscribeUrl", unsubscribeUrl);
        switch (template) {
            case "onboarding-reminder" -> vars.put("trackClickLink", "https://track.example/click/1");
            case "inactive-reminder" -> {
                vars.put("trackClickLink", "https://track.example/click/1");
                vars.put("rustoreLink", "https://rustore.example/app");
            }
            case "todo-reminder" -> {
                vars.put("todoName", "Полить теплицу");
                vars.put("listName", "Дача");
                vars.put("dueAt", "31.07.2026 18:00");
                vars.put("listUrl", "https://todo.keepware.ru");
            }
            default -> throw new IllegalArgumentException("Неизвестный шаблон: " + template);
        }
        return vars;
    }
}
