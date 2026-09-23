package ru.mngerasimenko.todolist.config;

import jakarta.validation.ConstraintViolation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import ru.mngerasimenko.todolist.dto.auth.RegisterRequest;

import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Валидатор из {@link I18nConfig}: язык сообщения внутри HTTP-запроса берётся из
 * {@code Accept-Language}, вне запроса — из {@link LocaleContextHolder}.
 * Сквозной сценарий через MockMvc — в {@code ValidationMessageLocalizationTest}.
 */
class I18nConfigValidatorTest {

    private static final String RU_PASSWORD_SIZE = "Пароль должен содержать от 5 до 128 символов";
    private static final String EN_PASSWORD_SIZE = "Password must be between 5 and 128 characters";

    private AnnotationConfigApplicationContext context;
    private LocalValidatorFactoryBean validator;

    @BeforeEach
    void setUp() {
        // Контекст из самой конфигурации, а не ручная сборка бина: так проверяется и то,
        // что бин объявлен так, как его создаст Spring (static @Bean + ObjectProvider).
        context = new AnnotationConfigApplicationContext(I18nConfig.class);
        validator = context.getBean(LocalValidatorFactoryBean.class);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        LocaleContextHolder.resetLocaleContext();
        context.close();
    }

    @Test
    @DisplayName("в запросе язык берётся из Accept-Language, а не из LocaleContextHolder")
    void insideRequest_AcceptLanguageWinsOverLocaleContext() {
        bindRequest("en-GB");
        LocaleContextHolder.setLocale(Locale.forLanguageTag("ru"));

        assertThat(shortPasswordMessage()).isEqualTo(EN_PASSWORD_SIZE);
    }

    @Test
    @DisplayName("в запросе без Accept-Language — ru, даже если контекст английский")
    void insideRequest_NoHeader_DefaultsToRussian() {
        bindRequest();
        LocaleContextHolder.setLocale(Locale.ENGLISH);

        assertThat(shortPasswordMessage()).isEqualTo(RU_PASSWORD_SIZE);
    }

    @Test
    @DisplayName("несколько строк Accept-Language учитываются вместе — как у 429 и контроллеров")
    void insideRequest_MultipleHeaderLines_AreJoined() {
        // По RFC 9110 несколько строк заголовка равны одной, склеенной через запятую. Первая строка
        // не поддерживается, вторая поддерживается: getHeader отдал бы только первую, и язык
        // сообщений валидации разошёлся бы с языком 429 на том же запросе.
        bindRequest("de", "en");

        assertThat(shortPasswordMessage()).isEqualTo(EN_PASSWORD_SIZE);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {"-", "*", "!@#", "en;q=abc", "ru-Cyrl-RU-x-private-use-subtags-and-more"})
    @DisplayName("враждебный заголовок не роняет валидацию, язык — ru по умолчанию")
    void insideRequest_HostileHeader_FallsBackToRussian(String hostile) {
        bindRequest(hostile);

        assertThat(shortPasswordMessage()).isEqualTo(RU_PASSWORD_SIZE);
    }

    @Test
    @DisplayName("заголовок разбирается один раз на запрос, а не на каждое нарушение")
    void insideRequest_HeaderIsParsedOncePerRequest() {
        CountingRequest request = new CountingRequest("en");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(shortPasswordMessage()).isEqualTo(EN_PASSWORD_SIZE);
        assertThat(shortPasswordMessage()).isEqualTo(EN_PASSWORD_SIZE);

        assertThat(request.headerReads).as("чтений Accept-Language за запрос").isEqualTo(1);
    }

    @Test
    @DisplayName("вне HTTP-запроса — локаль LocaleContextHolder")
    void outsideRequest_UsesLocaleContext() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(shortPasswordMessage()).isEqualTo(EN_PASSWORD_SIZE);

        LocaleContextHolder.setLocale(Locale.forLanguageTag("ru"));
        assertThat(shortPasswordMessage()).isEqualTo(RU_PASSWORD_SIZE);
    }

    /**
     * Заголовок отдаётся через переопределённые {@code getHeader}/{@code getHeaders}, а не
     * {@code addHeader}: сам {@code MockHttpServletRequest} на {@code addHeader("Accept-Language", ...)}
     * зовёт {@code HttpHeaders.getAcceptLanguageAsLocales()}, а тот падает на враждебных значениях
     * вроде {@code "-"} (JDK {@code LanguageRange.parse} бросает AIOOBE). Реальный Tomcat заголовок
     * при чтении не разбирает — стенд ближе к проду. Тот же приём в {@code RateLimitFilterTest}.
     */
    private static void bindRequest(String... acceptLanguageLines) {
        MockHttpServletRequest request = new MockHttpServletRequest() {
            @Override
            public String getHeader(String name) {
                return HttpHeaders.ACCEPT_LANGUAGE.equalsIgnoreCase(name)
                        ? (acceptLanguageLines.length == 0 ? null : acceptLanguageLines[0])
                        : super.getHeader(name);
            }

            @Override
            public Enumeration<String> getHeaders(String name) {
                return HttpHeaders.ACCEPT_LANGUAGE.equalsIgnoreCase(name)
                        ? Collections.enumeration(List.of(acceptLanguageLines))
                        : super.getHeaders(name);
            }
        };
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    /** Считает, сколько раз прочитан {@code Accept-Language} — для проверки кэша на запрос. */
    private static final class CountingRequest extends MockHttpServletRequest {

        private final String acceptLanguage;
        private int headerReads;

        private CountingRequest(String acceptLanguage) {
            this.acceptLanguage = acceptLanguage;
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (!HttpHeaders.ACCEPT_LANGUAGE.equalsIgnoreCase(name)) {
                return super.getHeaders(name);
            }
            headerReads++;
            return Collections.enumeration(List.of(acceptLanguage));
        }
    }

    private String shortPasswordMessage() {
        RegisterRequest request = RegisterRequest.builder()
                .email("new@mail.ru")
                .name("newuser")
                .password("12")
                .build();
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(request);
        assertThat(violations).hasSize(1);
        return violations.iterator().next().getMessage();
    }
}
