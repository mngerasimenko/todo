package ru.mngerasimenko.todolist.service;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.util.HtmlUtils;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import ru.mngerasimenko.todolist.config.I18nConfig;
import ru.mngerasimenko.todolist.crypto.CryptoService;
import ru.mngerasimenko.todolist.settings.EmailProperties;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты для EmailServiceImpl.
 * <p>
 * Основная часть — с замокированными JavaMailSender / TemplateEngine / MessageService: delivery flow
 * (что mailSender реально вызывается), graceful обработка ошибок SMTP и переменные, которые сервис
 * кладёт в {@link Context} шаблона (ссылка отписки, экранирование). Шаблоны сами по себе покрыты
 * в EmailTemplateRenderingTest, но переменные туда подставляет тест, а не сервис.
 * <p>
 * Тесты через {@link #renderedHtml} собирают сервис с настоящим Thymeleaf и бандлами из I18nConfig
 * и читают HTML из отправленного письма — так видно то, что получатель реально увидит на стыке
 * сервиса и шаблона.
 */
@ExtendWith(MockitoExtension.class)
class EmailServiceImplTest {

    private static final String UNSUBSCRIBE_URL = "https://todo.keepware.ru/api/users/unsubscribe-reminder";

    /** Плейсхолдер MessageFormat: {@code {0}}, {@code {1,number}}. */
    private static final Pattern MESSAGE_FORMAT_ARGUMENT = Pattern.compile("\\{\\d");

    /**
     * Нечётная серия апострофов ({@code '}, {@code '''}) — в тексте для MessageFormat последний
     * из них открывает кавычку, а не печатается.
     */
    private static final Pattern LONE_APOSTROPHE = Pattern.compile("(?<!')(?:'')*'(?!')");

    /** Сущность, экранированная повторно: {@code &amp;amp;}, {@code &amp;eacute;}, {@code &amp;#39;}, {@code &amp;frac12;}. */
    private static final Pattern DOUBLE_ESCAPED = Pattern.compile("&amp;(#\\d+|#x\\p{XDigit}+|[a-zA-Z][a-zA-Z0-9]*);");

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private SpringTemplateEngine templateEngine;

    @Mock
    private MessageService messageService;

    private EmailProperties emailProperties;
    private CryptoService cryptoService;
    private EmailServiceImpl emailService;

    @BeforeEach
    void setUp() {
        emailProperties = new EmailProperties();
        emailProperties.setFrom("todo-noreply@keepware.ru");
        emailProperties.setBaseUrl("https://todo.keepware.ru");
        emailProperties.setVerificationTokenTtlHours(24);
        emailProperties.setResetTokenTtlHours(1);
        // Реальный CryptoService с тестовым ключом — подпись трекинг-ссылок реально считается
        cryptoService = new CryptoService(Base64.getEncoder().encodeToString(
                "01234567890123456789012345678901".getBytes()));
        emailService = new EmailServiceImpl(mailSender, emailProperties, templateEngine, messageService, cryptoService);

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

    @Test
    void sendInactiveReminderEmail_BuildsSignedTrackLinks() {
        MimeMessage mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        emailService.sendInactiveReminderEmail("user@example.com", "Иван", 42L, "ru");

        // Захватываем Context, переданный в шаблон, и проверяем, что трекинг-ссылки подписаны
        ArgumentCaptor<Context> ctx = ArgumentCaptor.forClass(Context.class);
        verify(templateEngine).process(eq("inactive-reminder"), ctx.capture());

        String openLink = (String) ctx.getValue().getVariable("trackOpenLink");
        String clickLink = (String) ctx.getValue().getVariable("trackClickLink");

        assertThat(openLink).startsWith("https://todo.keepware.ru/api/track/open/42?s=");
        assertThat(clickLink).startsWith("https://todo.keepware.ru/api/track/click/42?s=");
        // подпись валидна и привязана к типу события + userId
        assertThat(cryptoService.verifySignature("open:42",
                openLink.substring(openLink.indexOf("?s=") + 3))).isTrue();
        assertThat(cryptoService.verifySignature("click:42",
                clickLink.substring(clickLink.indexOf("?s=") + 3))).isTrue();
    }

    @Test
    void sendOnboardingReminderEmail_BuildsSignedTrackLinks() {
        MimeMessage mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        emailService.sendOnboardingReminderEmail("user@example.com", "Иван", 43L, "ru", null);

        ArgumentCaptor<Context> ctx = ArgumentCaptor.forClass(Context.class);
        verify(templateEngine).process(eq("onboarding-reminder"), ctx.capture());

        String openLink = (String) ctx.getValue().getVariable("trackOpenLink");
        String clickLink = (String) ctx.getValue().getVariable("trackClickLink");

        assertThat(openLink).startsWith("https://todo.keepware.ru/api/track/open/43?s=");
        assertThat(clickLink).startsWith("https://todo.keepware.ru/api/track/click/43?s=");
        assertThat(cryptoService.verifySignature("open:43",
                openLink.substring(openLink.indexOf("?s=") + 3))).isTrue();
        assertThat(cryptoService.verifySignature("click:43",
                clickLink.substring(clickLink.indexOf("?s=") + 3))).isTrue();
    }

    // === Ссылка отписки: шаблон рендерит footer только по unsubscribeUrl, собирает его сервис ===

    @Test
    void sendInactiveReminderEmail_PutsUnsubscribeUrlWithToken() {
        when(mailSender.createMimeMessage()).thenReturn(mock(MimeMessage.class));

        emailService.sendInactiveReminderEmail("user@example.com", "Иван", 42L, "ru", "tok-123");

        assertThat(capturedContext("inactive-reminder").getVariable("unsubscribeUrl"))
                .isEqualTo(UNSUBSCRIBE_URL + "?token=tok-123");
    }

    @Test
    void sendOnboardingReminderEmail_PutsUnsubscribeUrlWithToken() {
        when(mailSender.createMimeMessage()).thenReturn(mock(MimeMessage.class));

        emailService.sendOnboardingReminderEmail("user@example.com", "Иван", 42L, "ru", "tok-123");

        assertThat(capturedContext("onboarding-reminder").getVariable("unsubscribeUrl"))
                .isEqualTo(UNSUBSCRIBE_URL + "?token=tok-123");
    }

    @Test
    void sendTodoDueEmail_PutsUnsubscribeUrlWithTodoDueScope() {
        when(mailSender.createMimeMessage()).thenReturn(mock(MimeMessage.class));

        emailService.sendTodoDueEmail("user@example.com", "Иван", "Молоко", "Покупки", "31.07.2026 18:00",
                42L, "ru", "tok-123");

        // Без scope=todo_due отписка из этого письма выключила бы не то согласие (reminder_opt_out).
        assertThat(capturedContext("todo-reminder").getVariable("unsubscribeUrl"))
                .isEqualTo(UNSUBSCRIBE_URL + "?token=tok-123&scope=todo_due");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void reminderEmails_OmitUnsubscribeUrl_WhenTokenMissing(String token) {
        when(mailSender.createMimeMessage()).thenReturn(mock(MimeMessage.class));

        emailService.sendInactiveReminderEmail("user@example.com", "Иван", 42L, "ru", token);
        emailService.sendOnboardingReminderEmail("user@example.com", "Иван", 42L, "ru", token);
        emailService.sendTodoDueEmail("user@example.com", "Иван", "Молоко", "Покупки", "31.07.2026 18:00",
                42L, "ru", token);

        // null — сигнал шаблону не рендерить footer: ссылка с пустым токеном вела бы на страницу ошибки.
        for (String template : List.of("inactive-reminder", "onboarding-reminder", "todo-reminder")) {
            assertThat(capturedContext(template).getVariable("unsubscribeUrl")).as(template).isNull();
        }
    }

    // === Экранирование пользовательского текста ===

    @ParameterizedTest
    @ValueSource(strings = {"José", "Ann & Kate"})
    void reminderEmails_PassUserNameUnescaped_BecauseGreetingIsThText(String userName) {
        when(mailSender.createMimeMessage()).thenReturn(mock(MimeMessage.class));

        emailService.sendInactiveReminderEmail("user@example.com", userName, 42L, "ru", null);
        emailService.sendOnboardingReminderEmail("user@example.com", userName, 42L, "ru", null);
        emailService.sendTodoDueEmail("user@example.com", userName, "Молоко", "Покупки", "31.07.2026 18:00",
                42L, "ru", null);

        for (String template : List.of("inactive-reminder", "onboarding-reminder", "todo-reminder")) {
            assertThat(capturedContext(template).getVariable("userName")).as(template).isEqualTo(userName);
        }
    }

    @Test
    void sendTodoDueEmail_EscapesTodoAndListNames_BecauseBodyIsThUtext() {
        when(mailSender.createMimeMessage()).thenReturn(mock(MimeMessage.class));

        emailService.sendTodoDueEmail("user@example.com", "Иван", "<b>Milk</b> & eggs", "Mom's <list>",
                "31.07.2026 18:00", 42L, "ru", null);

        // todo-reminder.html подставляет эти два значения через th:utext — экранирование сервиса
        // единственное, что отделяет название задачи от разметки письма.
        Context ctx = capturedContext("todo-reminder");
        assertThat(ctx.getVariable("todoName")).isEqualTo("&lt;b&gt;Milk&lt;/b&gt; &amp; eggs");
        assertThat(ctx.getVariable("listName")).isEqualTo("Mom&#39;s &lt;list&gt;");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "José               | Hi, José!",
            "Ann & Kate         | Hi, Ann &amp; Kate!",
            "<script>x</script> | Hi, &lt;script&gt;x&lt;/script&gt;!"
    })
    void reminderEmails_RenderUserNameEscapedExactlyOnce(String userName, String expectedGreeting) throws Exception {
        List<String> emails = List.of(
                renderedHtml(s -> s.sendInactiveReminderEmail("user@example.com", userName, 42L, "en", null)),
                renderedHtml(s -> s.sendOnboardingReminderEmail("user@example.com", userName, 42L, "en", null)),
                renderedHtml(s -> s.sendTodoDueEmail("user@example.com", userName, "Milk", "Shopping",
                        "31.07.2026 18:00", 42L, "en", null)));

        // Повторное экранирование видно получателю буквально: «Hi, Ann &amp; Kate!», «Hi, Jos&eacute;!».
        assertThat(emails).allSatisfy(html -> assertThat(html)
                .contains(expectedGreeting)
                .doesNotContain("<script>")
                .doesNotContainPattern(DOUBLE_ESCAPED));
    }

    @Test
    void utextBodies_RenderServiceEscapedNamesExactlyOnce() throws Exception {
        String invite = renderedHtml(s -> s.sendInviteEmail("user@example.com", "https://todo.keepware.ru/invite/abc",
                "<script>x</script>", "Ann & Kate", "en"));
        String todoDue = renderedHtml(s -> s.sendTodoDueEmail("user@example.com", "Anna", "Milk & <eggs>", "Mom's",
                "31.07.2026 18:00", 42L, "en", null));

        // Обратная сторона правки имени: вступление invite.html и тело todo-reminder.html идут через
        // th:utext, здесь экранирование сервиса единственное — убрать его значит пустить разметку в письмо.
        assertThat(invite)
                .contains("<strong>Ann &amp; Kate</strong>")
                .contains("&lt;script&gt;x&lt;/script&gt;")
                .doesNotContain("<script>")
                .doesNotContainPattern(DOUBLE_ESCAPED);
        assertThat(todoDue)
                .contains("Milk &amp; &lt;eggs&gt;")
                .contains("Mom&#39;s")
                .doesNotContainPattern(DOUBLE_ESCAPED);
    }

    // === Апострофы в текстах бандлов ===

    @Test
    void englishEmails_RenderApostrophesWithoutMessageFormatQuoting() throws Exception {
        // th:text пишет апостроф как &#39; — в сыром HTML буквальное '' выглядит «&#39;&#39;»,
        // поэтому проверяется текст, который видит получатель.
        String verification = HtmlUtils.htmlUnescape(
                renderedHtml(s -> s.sendVerificationEmail("user@example.com", "tok", "en")));
        String reset = HtmlUtils.htmlUnescape(
                renderedHtml(s -> s.sendPasswordResetEmail("user@example.com", "tok", "en")));
        String invite = HtmlUtils.htmlUnescape(
                renderedHtml(s -> s.sendInviteEmail("user@example.com", "https://todo.keepware.ru/invite/abc",
                        "Shopping", "John", "en")));
        String inactive = HtmlUtils.htmlUnescape(
                renderedHtml(s -> s.sendInactiveReminderEmail("user@example.com", "Anna", 42L, "en", "tok")));
        String onboarding = HtmlUtils.htmlUnescape(
                renderedHtml(s -> s.sendOnboardingReminderEmail("user@example.com", "Anna", 42L, "en", "tok")));
        String todoDue = HtmlUtils.htmlUnescape(
                renderedHtml(s -> s.sendTodoDueEmail("user@example.com", "Anna", "Milk", "Shopping",
                        "31.07.2026 18:00", 42L, "en", "tok")));

        assertThat(List.of(verification, reset, invite, inactive, onboarding, todoDue))
                .allSatisfy(text -> assertThat(text).doesNotContain("''"));
        // Тексты без аргументов — в бандле одиночный апостроф.
        assertThat(verification).contains("If the button doesn't work");
        assertThat(inactive).contains("You haven't visited the app").contains("maybe it's time");
        // Тексты с {0} — в бандле '', MessageFormat сводит к одному.
        assertThat(verification).contains("If you didn't sign up");
        assertThat(reset).contains("If you didn't request a password reset");
        assertThat(invite).contains("If you don't know the sender");
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "ru"})
    void messageBundles_QuoteApostrophesOnlyWhereMessageFormatRuns(String language) throws Exception {
        // Правило из шапки messages_en.properties. Без аргументов Spring отдаёт текст как есть
        // (alwaysUseMessageFormat в I18nConfig выключен) — '' дошло бы до читателя буквально; так берутся
        // страница отписки (unsubscribe.success.*) и все #{key} без параметров в шаблонах. С аргументами
        // текст идёт через MessageFormat — одиночный ' открывает кавычку: апостроф пропадает, {n} после
        // него не подставляется. Ключ классифицируется по тексту: сегодня аргументы передаются ровно
        // тем ключам, в тексте которых есть {n}.
        Properties bundle = new Properties();
        try (Reader reader = new InputStreamReader(Objects.requireNonNull(
                getClass().getResourceAsStream("/messages_" + language + ".properties")), StandardCharsets.UTF_8)) {
            bundle.load(reader);
        }
        MessageService messages = new MessageService(new I18nConfig().messageSource());
        Locale locale = Locale.forLanguageTag(language);
        Map<Boolean, List<String>> keysByArguments = bundle.stringPropertyNames().stream()
                .collect(Collectors.partitioningBy(key -> MESSAGE_FORMAT_ARGUMENT.matcher(bundle.getProperty(key)).find()));

        assertThat(keysByArguments.get(false)).isNotEmpty().allSatisfy(key -> assertThat(messages.getMessage(key, locale))
                .as(key)
                .isEqualTo(bundle.getProperty(key))
                .doesNotContain("''"));
        assertThat(keysByArguments.get(true)).isNotEmpty().allSatisfy(key -> assertThat(bundle.getProperty(key))
                .as(key)
                .doesNotContainPattern(LONE_APOSTROPHE));
    }

    private Context capturedContext(String template) {
        ArgumentCaptor<Context> ctx = ArgumentCaptor.forClass(Context.class);
        verify(templateEngine).process(eq(template), ctx.capture());
        return ctx.getValue();
    }

    /**
     * Отправить письмо через сервис с настоящим Thymeleaf и бандлами из I18nConfig
     * и вернуть HTML из отправленного MimeMessage.
     */
    private String renderedHtml(Consumer<EmailServiceImpl> send) throws Exception {
        MessageSource messageSource = new I18nConfig().messageSource();
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setTemplateEngineMessageSource(messageSource);

        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(message);

        send.accept(new EmailServiceImpl(mailSender, emailProperties, engine,
                new MessageService(messageSource), cryptoService));

        verify(mailSender).send(message);
        String html = htmlOf(message);
        assertThat(html).as("HTML-часть письма").isNotNull();
        return html;
    }

    /** MimeMessageHelper кладёт HTML в единственную текстовую часть внутри multipart/mixed → related. */
    private static String htmlOf(Part part) throws Exception {
        Object content = part.getContent();
        if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                String html = htmlOf(multipart.getBodyPart(i));
                if (html != null) {
                    return html;
                }
            }
            return null;
        }
        return content instanceof String text ? text : null;
    }
}
