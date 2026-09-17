package ru.mngerasimenko.todolist.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.mngerasimenko.todolist.config.I18nConfig;
import ru.mngerasimenko.todolist.config.TestSecurityConfig;
import ru.mngerasimenko.todolist.mapper.UserMapper;
import ru.mngerasimenko.todolist.security.ApiSecurityConfig;
import ru.mngerasimenko.todolist.service.RefreshTokenService;
import ru.mngerasimenko.todolist.service.TokenBlacklistService;
import ru.mngerasimenko.todolist.service.UserService;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Язык сообщений bean validation в ответе 400.
 *
 * <p>Прод-факт 17.09.2026: {@code POST /api/auth/register} с {@code Accept-Language: ru} и коротким
 * паролем отвечал {@code "Password must be between 5 and 128 characters"} — тексты были зашиты
 * в аннотации DTO. Язык выбирается тем же {@code AcceptLanguageParser}, что и у 429 и страницы
 * отписки: один заголовок не должен давать ответы на разных языках.
 *
 * <p>{@link I18nConfig} импортирован явно: {@code @WebMvcTest} не поднимает проектные
 * {@code @Configuration}, и без него срез работал бы на валидаторе и {@code MessageSource}
 * из автоконфигурации, а не на тех, что в проде.
 */
@WebMvcTest(AuthController.class)
@Import({ApiSecurityConfig.class, TestSecurityConfig.class, I18nConfig.class})
class ValidationMessageLocalizationTest {

    private static final String RU_PASSWORD_SIZE = "Пароль должен содержать от 5 до 128 символов";
    private static final String EN_PASSWORD_SIZE = "Password must be between 5 and 128 characters";

    private static final String SHORT_PASSWORD_REGISTRATION =
            "{\"email\":\"new@mail.ru\",\"name\":\"newuser\",\"password\":\"12\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthenticationManager authenticationManager;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private UserMapper userMapper;

    @MockitoBean
    private RefreshTokenService refreshTokenService;

    @MockitoBean
    private TokenBlacklistService tokenBlacklistService;

    @ParameterizedTest(name = "[{index}] Accept-Language: \"{0}\"")
    @CsvSource(delimiter = '|', textBlock = """
            ru                   | Пароль должен содержать от 5 до 128 символов
            ru-RU                | Пароль должен содержать от 5 до 128 символов
            en                   | Password must be between 5 and 128 characters
            en-US,en;q=0.9       | Password must be between 5 and 128 characters
            ru;q=0.1,en;q=0.9    | Password must be between 5 and 128 characters
            de                   | Пароль должен содержать от 5 до 128 символов
            de,en;q=0.5          | Password must be between 5 and 128 characters
            en;q=0,ru;q=0.2      | Пароль должен содержать от 5 до 128 символов
            """)
    @DisplayName("язык сообщения — поддерживаемый язык с наибольшим q-весом, иначе ru")
    void register_ShortPassword_MessageFollowsAcceptLanguage(String acceptLanguage, String expected)
            throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SHORT_PASSWORD_REGISTRATION))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.message.password").value(expected));

        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("без Accept-Language — ru, как у 429 и страницы отписки (Android заголовок не шлёт)")
    void register_ShortPassword_NoAcceptLanguage_RussianMessage() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SHORT_PASSWORD_REGISTRATION))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.password").value(RU_PASSWORD_SIZE));
    }

    @Test
    @DisplayName("плейсхолдер {max} подставляется и в русский текст, и в английский")
    void login_OverlongEmail_MaxPlaceholderInterpolatedInBothLanguages() throws Exception {
        String overlongEmail = "a".repeat(64) + "@" + "b".repeat(61) + ".io";
        String body = "{\"email\":\"" + overlongEmail + "\",\"password\":\"password123\"}";

        mockMvc.perform(post("/api/auth/login")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "ru")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.email").value("Email не должен быть длиннее 128 символов"));

        mockMvc.perform(post("/api/auth/login")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.email").value("Email must not exceed 128 characters"));
    }

    @Test
    @DisplayName("все нарушения одного запроса — на одном языке")
    void register_SeveralViolations_AllInRequestedLanguage() throws Exception {
        String body = "{\"email\":\"not-an-email\",\"name\":\"<x>\",\"password\":\"12\"}";

        mockMvc.perform(post("/api/auth/register")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "ru")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.email").value("Неверный формат email"))
                .andExpect(jsonPath("$.message.name").value("Имя содержит недопустимые символы"))
                .andExpect(jsonPath("$.message.password").value(RU_PASSWORD_SIZE));

        mockMvc.perform(post("/api/auth/register")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.email").value("Invalid email format"))
                .andExpect(jsonPath("$.message.name").value("Name contains invalid characters"))
                .andExpect(jsonPath("$.message.password").value(EN_PASSWORD_SIZE));
    }
}
