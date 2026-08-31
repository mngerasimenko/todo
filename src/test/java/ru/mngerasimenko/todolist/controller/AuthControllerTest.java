package ru.mngerasimenko.todolist.controller;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import ru.mngerasimenko.todolist.config.TestSecurityConfig;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.dto.UserResponse;
import ru.mngerasimenko.todolist.dto.auth.*;
import ru.mngerasimenko.todolist.dto.validation.EmailValidation;
import ru.mngerasimenko.todolist.dto.validation.LocaleValidation;
import ru.mngerasimenko.todolist.exception.TokenExpiredException;
import ru.mngerasimenko.todolist.mapper.UserMapper;
import ru.mngerasimenko.todolist.security.ApiSecurityConfig;
import ru.mngerasimenko.todolist.security.jwt.JwtProperties;
import ru.mngerasimenko.todolist.security.jwt.JwtTokenProvider;
import ru.mngerasimenko.todolist.service.RefreshTokenService;
import ru.mngerasimenko.todolist.service.RefreshTokenService.RefreshTokenRotationResult;
import ru.mngerasimenko.todolist.service.TokenBlacklistService;
import ru.mngerasimenko.todolist.service.UserService;

import java.util.Collections;

import jakarta.servlet.http.Cookie;
import org.springframework.http.HttpHeaders;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Тесты для AuthController
 * Проверяют работу эндпоинтов аутентификации: login, register, refresh
 */
@WebMvcTest(AuthController.class)
@Import({ApiSecurityConfig.class, TestSecurityConfig.class})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** Нужен для прямых вызовов package-private resolveEmailLocale в обход MockMvc. */
    @Autowired
    private AuthController authController;

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

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private JwtProperties jwtProperties;

    private UserDto testUserDto;
    private UserResponse testUserResponse;

    @BeforeEach
    void setUp() {
        // Подготовка тестовых данных
        testUserDto = UserDto.builder()
                .id(1L)
                .email("test@example.com")
                .name("testUser")
                .password("hashedPassword")
                .build();

        testUserResponse = UserResponse.builder()
                .id(1L)
                .email("test@example.com")
                .name("testUser")
                .build();

        // Настройка JwtProperties
        when(jwtProperties.getAccessTokenExpiration()).thenReturn(3600000L); // 1 час
        // Параметры refresh-cookie (веб-клиент, #259 httpOnly-cookie)
        when(jwtProperties.getRefreshTokenExpiration()).thenReturn(604800000L); // 7 дней
        when(jwtProperties.isRefreshCookieSecure()).thenReturn(true);
    }

    // ==================== LOGIN TESTS ====================

    @Test
    void login_ValidCredentials_ReturnsTokensAndUserInfo() throws Exception {
        // Arrange
        LoginRequest loginRequest = LoginRequest.builder()
                .email("test@example.com")
                .password("password123")
                .build();

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                "test@example.com",
                "password123",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
        );

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(authentication);
        when(userService.getUserByEmail("test@example.com")).thenReturn(testUserDto);
        when(userMapper.toResponse(testUserDto)).thenReturn(testUserResponse);
        when(jwtTokenProvider.generateAccessToken(any(Authentication.class)))
                .thenReturn("access-token-123");
        when(refreshTokenService.createRefreshToken(1L))
                .thenReturn("refresh-token-123");

        // Act & Assert
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.access_token").value("access-token-123"))
                .andExpect(jsonPath("$.refresh_token").value("refresh-token-123"))
                .andExpect(jsonPath("$.expires_in").value(3600)) // 3600000 / 1000
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.user.id").value(1))
                .andExpect(jsonPath("$.user.email").value("test@example.com"))
                .andExpect(jsonPath("$.user.name").value("testUser"));
    }

    // #259: login дополнительно ставит refresh-токен в HttpOnly-cookie для веб-клиента.
    // Тело ответа с refresh_token сохраняется (Android читает его оттуда).
    @Test
    void login_SetsHttpOnlyRefreshCookie() throws Exception {
        LoginRequest loginRequest = LoginRequest.builder()
                .email("test@example.com").password("password123").build();
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                "test@example.com", "password123",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(authentication);
        when(userService.getUserByEmail("test@example.com")).thenReturn(testUserDto);
        when(userMapper.toResponse(testUserDto)).thenReturn(testUserResponse);
        when(jwtTokenProvider.generateAccessToken(any(Authentication.class))).thenReturn("access-token-123");
        when(refreshTokenService.createRefreshToken(1L)).thenReturn("refresh-token-123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                // cookie присутствует и правильно атрибутирована
                .andExpect(cookie().value("refresh_token", "refresh-token-123"))
                .andExpect(cookie().httpOnly("refresh_token", true))
                .andExpect(cookie().secure("refresh_token", true))
                .andExpect(cookie().path("refresh_token", "/api/auth"))
                .andExpect(cookie().maxAge("refresh_token", 604800))
                // SameSite=Strict проверяем по сырому заголовку (Servlet Cookie его не моделирует)
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Strict")))
                // тело по-прежнему содержит refresh_token (для Android)
                .andExpect(jsonPath("$.refresh_token").value("refresh-token-123"));
    }

    @Test
    void register_SetsHttpOnlyRefreshCookie() throws Exception {
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email("new@example.com").name("newUser").password("password123").build();
        UserDto newUserDto = UserDto.builder().id(2L).email("new@example.com").name("newUser").build();
        when(userService.createUser(any(UserDto.class))).thenReturn(newUserDto);
        when(jwtTokenProvider.generateAccessToken("new@example.com")).thenReturn("new-access-token");
        when(refreshTokenService.createRefreshToken(2L)).thenReturn("new-refresh-token");
        when(userMapper.toResponse(newUserDto)).thenReturn(UserResponse.builder().id(2L).build());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andExpect(cookie().value("refresh_token", "new-refresh-token"))
                .andExpect(cookie().httpOnly("refresh_token", true))
                .andExpect(cookie().path("refresh_token", "/api/auth"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Strict")))
                .andExpect(jsonPath("$.refresh_token").value("new-refresh-token"));
    }

    @Test
    void login_InvalidCredentials_ReturnsUnauthorized() throws Exception {
        // Arrange
        LoginRequest loginRequest = LoginRequest.builder()
                .email("test@example.com")
                .password("wrongPassword")
                .build();

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        // Act & Assert
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void login_EmptyUsername_ReturnsBadRequest() throws Exception {
        // Arrange
        LoginRequest loginRequest = LoginRequest.builder()
                .email("")
                .password("password123")
                .build();

        // Act & Assert
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.email").exists());
    }

    @Test
    void login_EmptyPassword_ReturnsBadRequest() throws Exception {
        // Arrange
        LoginRequest loginRequest = LoginRequest.builder()
                .email("test@example.com")
                .password("")
                .build();

        // Act & Assert
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.password").exists());
    }

    @Test
    void login_NullFields_ReturnsBadRequest() throws Exception {
        // Arrange
        String invalidJson = "{\"email\": null, \"password\": null}";

        // Act & Assert
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }

    // ==================== REGISTER TESTS ====================

    @Test
    void register_ValidData_ReturnsCreatedWithTokens() throws Exception {
        // Arrange
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email("new@example.com")
                .name("newUser")
                .password("password123")
                .build();

        UserDto newUserDto = UserDto.builder()
                .id(2L)
                .email("new@example.com")
                .name("newUser")
                .password("hashedPassword")
                .build();

        UserResponse newUserResponse = UserResponse.builder()
                .id(2L)
                .email("new@example.com")
                .name("newUser")
                .build();

        when(userService.createUser(any(UserDto.class))).thenReturn(newUserDto);
        when(jwtTokenProvider.generateAccessToken("new@example.com")).thenReturn("new-access-token");
        when(refreshTokenService.createRefreshToken(2L)).thenReturn("new-refresh-token");
        when(userMapper.toResponse(newUserDto)).thenReturn(newUserResponse);

        // Act & Assert
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.access_token").value("new-access-token"))
                .andExpect(jsonPath("$.refresh_token").value("new-refresh-token"))
                .andExpect(jsonPath("$.expires_in").value(3600))
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.user.id").value(2))
                .andExpect(jsonPath("$.user.email").value("new@example.com"))
                .andExpect(jsonPath("$.user.name").value("newUser"));
    }

    // === preferredEmailLocale resolution: locale в DTO → Accept-Language → "ru" ===

    @Test
    void register_WithExplicitLocale_PassesLocaleToService() throws Exception {
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email("explicit@example.com").name("user").password("password123")
                .locale("en")
                .build();
        UserDto stubDto = UserDto.builder().id(2L).email("explicit@example.com").name("user").build();
        when(userService.createUser(any(UserDto.class))).thenReturn(stubDto);
        when(userMapper.toResponse(any(UserDto.class))).thenReturn(UserResponse.builder().id(2L).build());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Accept-Language", "ru") // Accept-Language игнорируется когда locale явный
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        ArgumentCaptor<UserDto> captor = ArgumentCaptor.forClass(UserDto.class);
        verify(userService).createUser(captor.capture());
        assertThat(captor.getValue().getPreferredEmailLocale()).isEqualTo("en");
    }

    @Test
    void register_WithoutLocale_UsesAcceptLanguageHeader() throws Exception {
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email("header@example.com").name("user").password("password123")
                .build();
        UserDto stubDto = UserDto.builder().id(2L).email("header@example.com").name("user").build();
        when(userService.createUser(any(UserDto.class))).thenReturn(stubDto);
        when(userMapper.toResponse(any(UserDto.class))).thenReturn(UserResponse.builder().id(2L).build());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Accept-Language", "en-US,en;q=0.9,ru;q=0.8")
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        ArgumentCaptor<UserDto> captor = ArgumentCaptor.forClass(UserDto.class);
        verify(userService).createUser(captor.capture());
        // Из заголовка берётся самый приемлемый тег, приведённый к нижнему регистру
        assertThat(captor.getValue().getPreferredEmailLocale()).isEqualTo("en-us");
    }

    @Test
    void register_WithoutLocaleAndHeader_DefaultsToRu() throws Exception {
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email("default@example.com").name("user").password("password123")
                .build();
        UserDto stubDto = UserDto.builder().id(2L).email("default@example.com").name("user").build();
        when(userService.createUser(any(UserDto.class))).thenReturn(stubDto);
        when(userMapper.toResponse(any(UserDto.class))).thenReturn(UserResponse.builder().id(2L).build());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        ArgumentCaptor<UserDto> captor = ArgumentCaptor.forClass(UserDto.class);
        verify(userService).createUser(captor.capture());
        assertThat(captor.getValue().getPreferredEmailLocale()).isEqualTo("ru");
    }

    @Test
    void register_WildcardAcceptLanguage_DefaultsToRu() throws Exception {
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email("wild@example.com").name("user").password("password123")
                .build();
        UserDto stubDto = UserDto.builder().id(2L).email("wild@example.com").name("user").build();
        when(userService.createUser(any(UserDto.class))).thenReturn(stubDto);
        when(userMapper.toResponse(any(UserDto.class))).thenReturn(UserResponse.builder().id(2L).build());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Accept-Language", "*")
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        ArgumentCaptor<UserDto> captor = ArgumentCaptor.forClass(UserDto.class);
        verify(userService).createUser(captor.capture());
        assertThat(captor.getValue().getPreferredEmailLocale()).isEqualTo("ru");
    }

    /**
     * Ill-formed language range в {@code Accept-Language} («-», «ru,-», «-;q=0.5») роняет
     * {@code Locale.LanguageRange.parse} через {@code ArrayIndexOutOfBoundsException},
     * а не через {@code IllegalArgumentException} — на публичном /api/auth/register это
     * давало HTTP 500 вместо регистрации.
     * <p>
     * Тест бьёт по {@code resolveEmailLocale} напрямую, а не через MockMvc: тот же дефект JDK
     * ломает и сам {@code MockHttpServletRequest.addHeader}, который парсит Accept-Language
     * при построении запроса, — до контроллера такой заголовок в MockMvc просто не долетает.
     */
    @Test
    void resolveEmailLocale_MalformedAcceptLanguage_DefaultsToRu() {
        assertThat(authController.resolveEmailLocale(null, "-")).isEqualTo("ru");
        assertThat(authController.resolveEmailLocale(null, "  -  ")).isEqualTo("ru");
        assertThat(authController.resolveEmailLocale(null, "-;q=0.5")).isEqualTo("ru");
        assertThat(authController.resolveEmailLocale(null, ",,,")).isEqualTo("ru");
    }

    @Test
    void resolveEmailLocale_MalformedRangeAfterValidOne_UsesTheValidRange() {
        // Битый элемент в середине списка не должен обесценивать читаемые языки рядом.
        assertThat(authController.resolveEmailLocale(null, "en-GB,-")).isEqualToIgnoringCase("en-GB");
        assertThat(authController.resolveEmailLocale(null, "-,en-GB")).isEqualToIgnoringCase("en-GB");
    }

    @Test
    void resolveEmailLocale_HugeMalformedAcceptLanguage_DefaultsToRu() {
        // 8 КБ (дефолтный потолок Tomcat) сплошного мусора: разбор обязан быть конечным и не бросать.
        assertThat(authController.resolveEmailLocale(null, "-".repeat(8000))).isEqualTo("ru");
        assertThat(authController.resolveEmailLocale(null, ";".repeat(8000))).isEqualTo("ru");
    }

    @Test
    void resolveEmailLocale_ExplicitLocaleTooLong_FallsBackToDefault() {
        // @Size(max = 8) на RegisterRequest.locale делает эту ветку недостижимой снаружи,
        // но защита не должна сама производить мусор: срез "abcdefghij" по восьми символам
        // дал бы "abcdefgh" — значение, не проходящее LocaleValidation.PATTERN.
        assertThat(authController.resolveEmailLocale("abcdefghij", null)).isEqualTo("ru");
        assertThat(authController.resolveEmailLocale("zh-Hant-TW", null)).isEqualTo("zh");
    }

    @Test
    void resolveEmailLocale_AnyHeader_ProducesValueValidForTheColumn() {
        // Что бы ни пришло в заголовке, в preferred_email_locale обязана уехать валидная локаль.
        String[] headers = {
                "-", "*", "zh-Hant-TW", "en-US,en;q=0.9", "ru-Cyrl-RU-x-private-use-and-more",
                "en;q=0", "-".repeat(8000), "en;seq=0.1,ru;q=0.2", ",,,", "en_US"
        };
        for (String header : headers) {
            String locale = authController.resolveEmailLocale(null, header);
            assertThat(locale).as("locale from header %s", header)
                    .isNotNull()
                    .hasSizeLessThanOrEqualTo(LocaleValidation.MAX_LENGTH)
                    .matches(LocaleValidation.PATTERN);
        }
    }

    @Test
    void resolveEmailLocale_AcceptLanguageByQuality_PicksHighestWeightedRange() {
        // Выбор по q сам по себе не нов: LanguageRange.parse тоже отдавал ranges, отсортированные
        // по весу. Ново здесь только q=0 — RFC 9110 считает его «неприемлемо», а прежний разбор
        // всё равно сохранял такой язык в preferred_email_locale.
        assertThat(authController.resolveEmailLocale(null, "ru;q=0.1,en;q=0.9")).isEqualTo("en");
        assertThat(authController.resolveEmailLocale(null, "en;q=0")).isEqualTo("ru");
    }

    @Test
    void resolveEmailLocale_BlankExplicitLocale_FallsThroughToTheHeader() {
        // LocaleValidation.PATTERN_OPTIONAL разрешает пустую строку ради старых Android-клиентов
        // (см. его javadoc) — значит пустой locale обязан не «побеждать» заголовок.
        assertThat(authController.resolveEmailLocale("", "en-GB")).isEqualTo("en-gb");
        assertThat(authController.resolveEmailLocale("   ", "en-GB")).isEqualTo("en-gb");
        assertThat(authController.resolveEmailLocale("", null)).isEqualTo("ru");
    }

    @Test
    void resolveEmailLocale_ExplicitLocale_IsStoredAsSent() {
        // Явный locale клиента сохраняется как прислан, без нормализации регистра — в отличие
        // от разобранного из заголовка ("en-US" → "en-us"). Пиннится, чтобы асимметрия
        // не поменялась молча: сравнивать такие значения нужно регистронезависимо.
        assertThat(authController.resolveEmailLocale("PT-br", null)).isEqualTo("PT-br");
    }

    @Test
    void register_HugeAcceptLanguage_StoresValidLocaleWithoutFailing() throws Exception {
        // Tomcat пропускает заголовок до 8 КБ. Что бы там ни лежало, в preferred_email_locale
        // обязан попасть валидный BCP-47 тег не длиннее LocaleValidation.MAX_LENGTH —
        // колонка в БД узкая, а обрезка длинного тега по символам давала мусор вроде "zh-Hant-".
        StringBuilder header = new StringBuilder();
        for (int i = 0; header.length() < 8000; i++) {
            header.append("qa").append((char) ('a' + i % 26)).append("-x").append(i).append(";q=0.5,");
        }
        header.append("zz");

        RegisterRequest registerRequest = RegisterRequest.builder()
                .email("huge@example.com").name("user").password("password123")
                .build();
        UserDto stubDto = UserDto.builder().id(2L).email("huge@example.com").name("user").build();
        when(userService.createUser(any(UserDto.class))).thenReturn(stubDto);
        when(userMapper.toResponse(any(UserDto.class))).thenReturn(UserResponse.builder().id(2L).build());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Accept-Language", header.toString())
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        ArgumentCaptor<UserDto> captor = ArgumentCaptor.forClass(UserDto.class);
        verify(userService).createUser(captor.capture());
        // Побеждает первый из разобранных элементов: заголовок длиннее лимита разбора,
        // и хвост в него не попадает. Значение обязано быть валидной локалью, а не срезом мусора.
        String locale = captor.getValue().getPreferredEmailLocale();
        assertThat(locale).isEqualTo("qaa-x0")
                .hasSizeLessThanOrEqualTo(LocaleValidation.MAX_LENGTH)
                .matches(LocaleValidation.PATTERN);
    }

    @Test
    void register_HostileAcceptLanguageThroughTheServletStack_StillRegisters() throws Exception {
        // Через MockMvc враждебный "-" в одиночку не пронести: MockHttpServletRequest.addHeader
        // сам зовёт Locale.LanguageRange.parse и падает на нём. Но парсит он только ПЕРВОЕ
        // значение заголовка, поэтому вторая строка Accept-Language доезжает до контроллера
        // нетронутой, а склеит значения через запятую уже spring-web (RequestHeaderMethodArgument-
        // Resolver отдаёт String[], ArrayToStringConverter соединяет). Так
        // проверяется весь путь: DispatcherServlet → @RequestHeader → @Valid → resolveEmailLocale.
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email("servlet@example.com").name("user").password("password123")
                .build();
        UserDto stubDto = UserDto.builder().id(2L).email("servlet@example.com").name("user").build();
        when(userService.createUser(any(UserDto.class))).thenReturn(stubDto);
        when(userMapper.toResponse(any(UserDto.class))).thenReturn(UserResponse.builder().id(2L).build());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Accept-Language", "*")
                        .header("Accept-Language", "-,de-DE")
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        // Заголовок доехал как "*,-,de-DE": разбор обязан пройти сквозь wildcard и битый элемент
        // и всё равно найти язык — а не просто не упасть.
        ArgumentCaptor<UserDto> captor = ArgumentCaptor.forClass(UserDto.class);
        verify(userService).createUser(captor.capture());
        assertThat(captor.getValue().getPreferredEmailLocale()).isEqualTo("de-de");
    }

    @Test
    void register_OverlongLanguageTag_FallsBackToPrimarySubtag() throws Exception {
        // "zh-Hant-TW" длиннее 8 символов: обрезка по символам давала "zh-Hant-" — тег,
        // не проходящий LocaleValidation.PATTERN. Корректная деградация — primary subtag.
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email("overlong@example.com").name("user").password("password123")
                .build();
        UserDto stubDto = UserDto.builder().id(2L).email("overlong@example.com").name("user").build();
        when(userService.createUser(any(UserDto.class))).thenReturn(stubDto);
        when(userMapper.toResponse(any(UserDto.class))).thenReturn(UserResponse.builder().id(2L).build());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Accept-Language", "zh-Hant-TW")
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        ArgumentCaptor<UserDto> captor = ArgumentCaptor.forClass(UserDto.class);
        verify(userService).createUser(captor.capture());
        assertThat(captor.getValue().getPreferredEmailLocale()).isEqualTo("zh");
    }

    @Test
    void register_DuplicateEmail_ReturnsBadRequest() throws Exception {
        // Arrange
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email("existing@example.com")
                .name("newUser")
                .password("password123")
                .build();

        when(userService.createUser(any(UserDto.class)))
                .thenThrow(new IllegalArgumentException("Email existing@example.com is already taken"));

        // Act & Assert
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Email existing@example.com is already taken"));
    }

    @Test
    void register_EmptyEmail_ReturnsBadRequest() throws Exception {
        // Arrange
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email("")
                .name("newUser")
                .password("password123")
                .build();

        // Act & Assert
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.email").exists());
    }

    @Test
    void register_InvalidEmail_ReturnsBadRequest() throws Exception {
        // Arrange
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email("invalid-email")
                .name("newUser")
                .password("password123")
                .build();

        // Act & Assert
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.email").exists());
    }

    @Test
    void register_EmptyName_ReturnsBadRequest() throws Exception {
        // Arrange
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email("test@example.com")
                .name("")
                .password("password123")
                .build();

        // Act & Assert
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.name").exists());
    }

    @Test
    void register_EmptyPassword_ReturnsBadRequest() throws Exception {
        // Arrange
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email("test@example.com")
                .name("newUser")
                .password("")
                .build();

        // Act & Assert
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.password").exists());
    }

    @Test
    void register_AllFieldsEmpty_ReturnsBadRequest() throws Exception {
        // Arrange
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email("")
                .name("")
                .password("")
                .build();

        // Act & Assert
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.email").exists())
                .andExpect(jsonPath("$.message.name").exists())
                .andExpect(jsonPath("$.message.password").exists());
    }

    // ==================== REFRESH TOKEN TESTS ====================

    @Test
    void refresh_ValidToken_ReturnsNewTokens() throws Exception {
        // Arrange
        RefreshTokenRequest refreshTokenRequest = RefreshTokenRequest.builder()
                .refreshToken("valid-refresh-token")
                .build();

        when(refreshTokenService.rotateRefreshToken("valid-refresh-token"))
                .thenReturn(new RefreshTokenRotationResult("new-refresh-token", "test@example.com"));
        when(jwtTokenProvider.generateAccessToken("test@example.com")).thenReturn("new-access-token");
        when(userService.getUserByEmail("test@example.com")).thenReturn(testUserDto);
        when(userMapper.toResponse(testUserDto)).thenReturn(testUserResponse);

        // Act & Assert
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshTokenRequest)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.access_token").value("new-access-token"))
                .andExpect(jsonPath("$.refresh_token").value("new-refresh-token"))
                .andExpect(jsonPath("$.expires_in").value(3600))
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.user.id").value(1))
                .andExpect(jsonPath("$.user.name").value("testUser"));
    }

    @Test
    void refresh_InvalidToken_ReturnsUnauthorized() throws Exception {
        // Arrange
        RefreshTokenRequest refreshTokenRequest = RefreshTokenRequest.builder()
                .refreshToken("invalid-refresh-token")
                .build();

        when(refreshTokenService.rotateRefreshToken("invalid-refresh-token"))
                .thenThrow(new BadCredentialsException("Невалидный refresh-токен"));

        // Act & Assert
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshTokenRequest)))
                .andExpect(status().isUnauthorized());
    }

    // #259: refresh-токен может прийти либо в теле (Android), либо в HttpOnly-cookie (веб).
    // Пустой/null токен в теле БЕЗ cookie — это «токен не предоставлен» → 401 (раньше было 400
    // из-за @NotBlank; теперь тело опционально, отсутствие токена в обоих источниках = auth failure).
    @Test
    void refresh_EmptyTokenNoCookie_ReturnsUnauthorized() throws Exception {
        RefreshTokenRequest refreshTokenRequest = RefreshTokenRequest.builder()
                .refreshToken("")
                .build();

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshTokenRequest)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_NullTokenNoCookie_ReturnsUnauthorized() throws Exception {
        String invalidJson = "{\"refreshToken\": null}";

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_NoBodyNoCookie_ReturnsUnauthorized() throws Exception {
        // Ни тела, ни cookie — токен не предоставлен
        mockMvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized());
    }

    // Веб-путь: refresh-токен приходит из HttpOnly-cookie, тело пустое.
    @Test
    void refresh_ReadsTokenFromCookie_WhenBodyEmpty() throws Exception {
        when(refreshTokenService.rotateRefreshToken("cookie-refresh-token"))
                .thenReturn(new RefreshTokenRotationResult("new-refresh-token", "test@example.com"));
        when(jwtTokenProvider.generateAccessToken("test@example.com")).thenReturn("new-access-token");
        when(userService.getUserByEmail("test@example.com")).thenReturn(testUserDto);
        when(userMapper.toResponse(testUserDto)).thenReturn(testUserResponse);

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("refresh_token", "cookie-refresh-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").value("new-access-token"))
                // ротация выставляет новую cookie
                .andExpect(cookie().value("refresh_token", "new-refresh-token"))
                .andExpect(cookie().httpOnly("refresh_token", true));

        verify(refreshTokenService).rotateRefreshToken("cookie-refresh-token");
    }

    // Fail-closed на cookie-пути: невалидная cookie → сервис бросает → 401, ротации нет.
    @Test
    void refresh_InvalidCookie_ReturnsUnauthorized() throws Exception {
        when(refreshTokenService.rotateRefreshToken("bad-cookie-token"))
                .thenThrow(new BadCredentialsException("Невалидный refresh-токен"));

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("refresh_token", "bad-cookie-token")))
                .andExpect(status().isUnauthorized());
    }

    // Android-путь сохранён: токен в теле имеет приоритет над cookie.
    @Test
    void refresh_BodyTokenTakesPrecedenceOverCookie() throws Exception {
        RefreshTokenRequest refreshTokenRequest = RefreshTokenRequest.builder()
                .refreshToken("body-refresh-token")
                .build();
        when(refreshTokenService.rotateRefreshToken("body-refresh-token"))
                .thenReturn(new RefreshTokenRotationResult("new-refresh-token", "test@example.com"));
        when(jwtTokenProvider.generateAccessToken("test@example.com")).thenReturn("new-access-token");
        when(userService.getUserByEmail("test@example.com")).thenReturn(testUserDto);
        when(userMapper.toResponse(testUserDto)).thenReturn(testUserResponse);

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshTokenRequest))
                        .cookie(new Cookie("refresh_token", "cookie-refresh-token")))
                .andExpect(status().isOk());

        // должен использоваться токен из тела, не из cookie
        verify(refreshTokenService).rotateRefreshToken("body-refresh-token");
        verify(refreshTokenService, never()).rotateRefreshToken("cookie-refresh-token");
    }

    // ==================== CONTENT TYPE TESTS ====================

    @Test
    void login_ReturnsJsonContentType() throws Exception {
        // Arrange
        LoginRequest loginRequest = LoginRequest.builder()
                .email("test@example.com")
                .password("password123")
                .build();

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                "test@example.com", "password123"
        );

        when(authenticationManager.authenticate(any())).thenReturn(authentication);
        when(userService.getUserByEmail(anyString())).thenReturn(testUserDto);
        when(userMapper.toResponse(any())).thenReturn(testUserResponse);
        when(jwtTokenProvider.generateAccessToken(any(Authentication.class))).thenReturn("token");
        when(refreshTokenService.createRefreshToken(anyLong())).thenReturn("refresh");

        // Act & Assert
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_JSON_VALUE));
    }

    @Test
    void register_ReturnsJsonContentType() throws Exception {
        // Arrange
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email("test@example.com")
                .name("testUser")
                .password("password123")
                .build();

        when(userService.createUser(any())).thenReturn(testUserDto);
        when(jwtTokenProvider.generateAccessToken(anyString())).thenReturn("token");
        when(refreshTokenService.createRefreshToken(anyLong())).thenReturn("refresh");
        when(userMapper.toResponse(any())).thenReturn(testUserResponse);

        // Act & Assert
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_JSON_VALUE));
    }

    @Test
    void refresh_ReturnsJsonContentType() throws Exception {
        // Arrange
        RefreshTokenRequest refreshTokenRequest = RefreshTokenRequest.builder()
                .refreshToken("valid-token")
                .build();

        when(refreshTokenService.rotateRefreshToken("valid-token"))
                .thenReturn(new RefreshTokenRotationResult("new-refresh", "test@example.com"));
        when(jwtTokenProvider.generateAccessToken("test@example.com")).thenReturn("token");
        when(userService.getUserByEmail(anyString())).thenReturn(testUserDto);
        when(userMapper.toResponse(any())).thenReturn(testUserResponse);

        // Act & Assert
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshTokenRequest)))
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_JSON_VALUE));
    }

    // ==================== VERIFY EMAIL TESTS ====================

    @Test
    void verifyEmail_ValidToken_ReturnsOk() throws Exception {
        // Arrange
        VerifyEmailRequest request = new VerifyEmailRequest("valid-token-123");

        doNothing().when(userService).verifyEmail("valid-token-123");

        // Act & Assert
        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Email подтверждён"));
    }

    @Test
    void verifyEmail_ExpiredToken_ReturnsBadRequest() throws Exception {
        // Arrange
        VerifyEmailRequest request = new VerifyEmailRequest("expired-token");

        doThrow(new TokenExpiredException("Токен истёк"))
                .when(userService).verifyEmail("expired-token");

        // Act & Assert
        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Токен истёк"));
    }

    @Test
    void verifyEmail_EmptyToken_ReturnsBadRequest() throws Exception {
        // Arrange
        VerifyEmailRequest request = new VerifyEmailRequest("");

        // Act & Assert
        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.token").exists());
    }

    @Test
    void verifyEmail_NullToken_ReturnsBadRequest() throws Exception {
        // Arrange
        String invalidJson = "{\"token\": null}";

        // Act & Assert
        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }

    // ==================== RESEND VERIFICATION TESTS ====================

    @Test
    @WithMockUser(username = "user@example.com")
    void resendVerification_Authenticated_ReturnsOk() throws Exception {
        // Arrange — мокируем получение пользователя по email
        UserDto userDto = UserDto.builder().id(1L).name("user").email("user@example.com").build();
        when(userService.getUserByEmail("user@example.com")).thenReturn(userDto);
        doNothing().when(userService).resendVerificationEmail(1L);

        // Act & Assert
        mockMvc.perform(post("/api/auth/resend-verification"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Письмо отправлено"));
    }

    @Test
    void resendVerification_NoAuth_ReturnsUnauthorized() throws Exception {
        // Act & Assert — без JWT-токена ожидаем 401
        mockMvc.perform(post("/api/auth/resend-verification"))
                .andExpect(status().isUnauthorized());
    }

    // ==================== FORGOT PASSWORD TESTS ====================

    @Test
    void forgotPassword_ValidEmail_ReturnsOk() throws Exception {
        // Arrange
        ForgotPasswordRequest request = new ForgotPasswordRequest("test@example.com");

        doNothing().when(userService).initiatePasswordReset("test@example.com");

        // Act & Assert
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Если аккаунт существует, письмо отправлено"));
    }

    @Test
    void forgotPassword_EmptyEmail_ReturnsBadRequest() throws Exception {
        // Arrange
        ForgotPasswordRequest request = new ForgotPasswordRequest("");

        // Act & Assert
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.email").exists());
    }

    @Test
    void forgotPassword_InvalidEmail_ReturnsBadRequest() throws Exception {
        // Arrange
        ForgotPasswordRequest request = new ForgotPasswordRequest("not-an-email");

        // Act & Assert
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.email").exists());
    }

    @Test
    void forgotPassword_TooLongEmail_ReturnsBadRequest() throws Exception {
        // Arrange — email длиной MAX_LENGTH+1 (точно превышает @Size).
        // Local-part 64 (макс по RFC 5321), чтобы пройти @Email и сработал именно @Size.
        String overlongEmail = "a".repeat(64) + "@" + "b".repeat(EmailValidation.MAX_LENGTH - 67) + ".io";
        String expectedMessage = "Email must not exceed " + EmailValidation.MAX_LENGTH + " characters";
        ForgotPasswordRequest request = new ForgotPasswordRequest(overlongEmail);

        // Act & Assert
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.email").value(expectedMessage));

        verifyNoInteractions(userService);
    }

    // ==================== RESET PASSWORD TESTS ====================

    @Test
    void resetPassword_ValidData_ReturnsOk() throws Exception {
        // Arrange
        ResetPasswordRequest request = new ResetPasswordRequest("valid-token", "newPassword123");

        doNothing().when(userService).resetPassword("valid-token", "newPassword123");

        // Act & Assert
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Пароль изменён"));
    }

    @Test
    void resetPassword_ExpiredToken_ReturnsBadRequest() throws Exception {
        // Arrange
        ResetPasswordRequest request = new ResetPasswordRequest("expired-token", "newPassword123");

        doThrow(new TokenExpiredException("Токен сброса пароля истёк"))
                .when(userService).resetPassword("expired-token", "newPassword123");

        // Act & Assert
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Токен сброса пароля истёк"));
    }

    @Test
    void resetPassword_EmptyToken_ReturnsBadRequest() throws Exception {
        // Arrange
        ResetPasswordRequest request = new ResetPasswordRequest("", "newPassword123");

        // Act & Assert
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.token").exists());
    }

    @Test
    void resetPassword_EmptyPassword_ReturnsBadRequest() throws Exception {
        // Arrange
        ResetPasswordRequest request = new ResetPasswordRequest("valid-token", "");

        // Act & Assert
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.password").exists());
    }

    @Test
    void resetPassword_AllFieldsEmpty_ReturnsBadRequest() throws Exception {
        // Arrange
        ResetPasswordRequest request = new ResetPasswordRequest("", "");

        // Act & Assert
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.token").exists())
                .andExpect(jsonPath("$.message.password").exists());
    }

    @Test
    void resetPassword_TooShortPassword_ReturnsBadRequest() throws Exception {
        // Arrange — пароль короче 3 символов
        ResetPasswordRequest request = new ResetPasswordRequest("valid-token", "ab");

        // Act & Assert
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.password").exists());
    }

    // ==================== CHANGE EMAIL TESTS ====================

    @Test
    @WithMockUser(username = "user@example.com")
    void changeEmail_Authenticated_ReturnsOk() throws Exception {
        // Arrange — мокируем получение пользователя по email
        UserDto userDto = UserDto.builder().id(1L).name("user").email("user@example.com").build();
        when(userService.getUserByEmail("user@example.com")).thenReturn(userDto);
        doNothing().when(userService).changeEmail(1L, "new@example.com");

        ChangeEmailRequest request = new ChangeEmailRequest("new@example.com");

        // Act & Assert
        mockMvc.perform(post("/api/auth/change-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Письмо подтверждения отправлено на новый email"));

        verify(userService, times(1)).changeEmail(1L, "new@example.com");
    }

    @Test
    void changeEmail_NoAuth_ReturnsUnauthorized() throws Exception {
        // Act & Assert — без JWT-токена ожидаем 401
        ChangeEmailRequest request = new ChangeEmailRequest("new@example.com");

        mockMvc.perform(post("/api/auth/change-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "user@example.com")
    void changeEmail_EmptyEmail_ReturnsBadRequest() throws Exception {
        // Arrange — пустой email
        ChangeEmailRequest request = new ChangeEmailRequest("");

        // Act & Assert
        mockMvc.perform(post("/api/auth/change-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.email").exists());
    }

    @Test
    @WithMockUser(username = "user@example.com")
    void changeEmail_InvalidEmail_ReturnsBadRequest() throws Exception {
        // Arrange — некорректный формат email
        ChangeEmailRequest request = new ChangeEmailRequest("not-an-email");

        // Act & Assert
        mockMvc.perform(post("/api/auth/change-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.email").exists());
    }

    @Test
    @WithMockUser(username = "test@example.com")
    void changeEmail_TooLongEmail_ReturnsBadRequest() throws Exception {
        // Arrange — email длиной MAX_LENGTH+1 (точно превышает @Size).
        // Local-part 64 (макс по RFC 5321), чтобы пройти @Email и сработал именно @Size.
        String overlongEmail = "a".repeat(64) + "@" + "b".repeat(EmailValidation.MAX_LENGTH - 67) + ".io";
        String expectedMessage = "Email must not exceed " + EmailValidation.MAX_LENGTH + " characters";
        ChangeEmailRequest request = new ChangeEmailRequest(overlongEmail);

        // Act & Assert
        mockMvc.perform(post("/api/auth/change-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.email").value(expectedMessage));

        verifyNoInteractions(userService);
    }

    // ==================== LOGOUT TESTS ====================

    @Test
    @WithMockUser(username = "test@example.com")
    void logout_WithRefreshToken_ReturnsOk() throws Exception {
        // Arrange
        LogoutRequest logoutRequest = LogoutRequest.builder()
                .refreshToken("some-refresh-token")
                .build();

        when(jwtTokenProvider.validateAccessToken(anyString())).thenReturn(true);
        when(jwtTokenProvider.getExpirationFromToken(anyString()))
                .thenReturn(java.time.Instant.now().plusSeconds(3600));

        // Act & Assert
        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer test-access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(logoutRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Выход выполнен"));

        verify(tokenBlacklistService).blacklistAccessToken(eq("test-access-token"), any());
        verify(refreshTokenService).revokeByRawToken("some-refresh-token");
    }

    @Test
    @WithMockUser(username = "test@example.com")
    void logout_WithoutRefreshToken_ReturnsOk() throws Exception {
        // Arrange
        when(jwtTokenProvider.validateAccessToken(anyString())).thenReturn(true);
        when(jwtTokenProvider.getExpirationFromToken(anyString()))
                .thenReturn(java.time.Instant.now().plusSeconds(3600));

        // Act & Assert
        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer test-access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Выход выполнен"));

        verify(tokenBlacklistService).blacklistAccessToken(eq("test-access-token"), any());
        verify(refreshTokenService, never()).revokeByRawToken(anyString());
    }

    // #259: веб-logout не шлёт refresh в теле — сервер берёт его из HttpOnly-cookie и отзывает.
    @Test
    @WithMockUser(username = "test@example.com")
    void logout_RevokesRefreshFromCookie_WhenBodyEmpty() throws Exception {
        when(jwtTokenProvider.validateAccessToken(anyString())).thenReturn(true);
        when(jwtTokenProvider.getExpirationFromToken(anyString()))
                .thenReturn(java.time.Instant.now().plusSeconds(3600));

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer test-access-token")
                        .cookie(new Cookie("refresh_token", "cookie-refresh-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        verify(tokenBlacklistService).blacklistAccessToken(eq("test-access-token"), any());
        verify(refreshTokenService).revokeByRawToken("cookie-refresh-token");
    }

    // #259: logout гасит refresh-cookie (Max-Age=0), чтобы браузер её удалил.
    @Test
    @WithMockUser(username = "test@example.com")
    void logout_ClearsRefreshCookie() throws Exception {
        when(jwtTokenProvider.validateAccessToken(anyString())).thenReturn(true);
        when(jwtTokenProvider.getExpirationFromToken(anyString()))
                .thenReturn(java.time.Instant.now().plusSeconds(3600));

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer test-access-token")
                        .cookie(new Cookie("refresh_token", "cookie-refresh-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(cookie().maxAge("refresh_token", 0))
                .andExpect(cookie().value("refresh_token", ""));
    }

    // Приоритет тела над cookie сохраняется и на logout (Android шлёт refresh в теле).
    @Test
    @WithMockUser(username = "test@example.com")
    void logout_BodyRefreshTakesPrecedenceOverCookie() throws Exception {
        LogoutRequest logoutRequest = LogoutRequest.builder()
                .refreshToken("body-refresh-token").build();
        when(jwtTokenProvider.validateAccessToken(anyString())).thenReturn(true);
        when(jwtTokenProvider.getExpirationFromToken(anyString()))
                .thenReturn(java.time.Instant.now().plusSeconds(3600));

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer test-access-token")
                        .cookie(new Cookie("refresh_token", "cookie-refresh-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(logoutRequest)))
                .andExpect(status().isOk());

        verify(refreshTokenService).revokeByRawToken("body-refresh-token");
        verify(refreshTokenService, never()).revokeByRawToken("cookie-refresh-token");
    }

    @Test
    @WithMockUser(username = "test@example.com")
    void logout_MasksEmailInInfoLog() throws Exception {
        // Arrange
        when(jwtTokenProvider.validateAccessToken(anyString())).thenReturn(true);
        when(jwtTokenProvider.getExpirationFromToken(anyString()))
                .thenReturn(java.time.Instant.now().plusSeconds(3600));

        Logger logger = (Logger) LoggerFactory.getLogger(AuthController.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            mockMvc.perform(post("/api/auth/logout")
                            .header("Authorization", "Bearer test-access-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk());
        } finally {
            logger.detachAppender(appender);
        }

        String logged = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("Выход пользователя"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Ожидался INFO о выходе пользователя"));
        assertThat(logged).contains("te***@example.com");
        assertThat(logged).doesNotContain("test@example.com");
    }

    @Test
    void logout_Unauthenticated_ReturnsUnauthorized() throws Exception {
        // Act & Assert
        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // ==================== CHANGE PASSWORD TESTS ====================

    @Test
    @WithMockUser(username = "test@example.com")
    void changePassword_Valid_ReturnsNewTokensAndBlacklistsOld() throws Exception {
        when(userService.getUserByEmail("test@example.com")).thenReturn(testUserDto);
        when(userMapper.toResponse(testUserDto)).thenReturn(testUserResponse);
        doNothing().when(userService).changePassword(1L, "oldPass", "newPass123");
        when(jwtTokenProvider.getExpirationFromToken(anyString()))
                .thenReturn(java.time.Instant.now().plusSeconds(3600));
        when(jwtTokenProvider.generateAccessToken("test@example.com")).thenReturn("new-access-token");
        when(refreshTokenService.createRefreshToken(1L)).thenReturn("new-refresh-token");

        ChangePasswordRequest request = ChangePasswordRequest.builder()
                .currentPassword("oldPass").newPassword("newPass123").build();

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer old-access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").value("new-access-token"))
                .andExpect(jsonPath("$.refresh_token").value("new-refresh-token"))
                .andExpect(jsonPath("$.user.email").value("test@example.com"));

        verify(userService).changePassword(1L, "oldPass", "newPass123");
        verify(tokenBlacklistService).blacklistAccessToken(eq("old-access-token"), any());
    }

    @Test
    void changePassword_NoAuth_ReturnsUnauthorized() throws Exception {
        ChangePasswordRequest request = ChangePasswordRequest.builder()
                .currentPassword("oldPass").newPassword("newPass123").build();
        mockMvc.perform(post("/api/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "test@example.com")
    void changePassword_WrongCurrent_ReturnsBadRequest() throws Exception {
        when(userService.getUserByEmail("test@example.com")).thenReturn(testUserDto);
        doThrow(new IllegalArgumentException("Текущий пароль неверен"))
                .when(userService).changePassword(1L, "wrong", "newPass123");

        ChangePasswordRequest request = ChangePasswordRequest.builder()
                .currentPassword("wrong").newPassword("newPass123").build();

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer old-access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(refreshTokenService, never()).createRefreshToken(anyLong());
        // #5b: blacklist не должен вызываться при неверном текущем пароле
        verify(tokenBlacklistService, never()).blacklistAccessToken(anyString(), any());
    }

    @Test
    @WithMockUser(username = "test@example.com")
    void changePassword_NonBearerHeader_ReturnsUnauthorizedAndNoMutation() throws Exception {
        ChangePasswordRequest request = ChangePasswordRequest.builder()
                .currentPassword("oldPass").newPassword("newPass123").build();
        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Basic something")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
        verify(userService, never()).changePassword(anyLong(), anyString(), anyString());
    }

    @Test
    @WithMockUser(username = "ghost@example.com")
    void changePassword_UserNotFound_ReturnsUnauthorized() throws Exception {
        when(userService.getUserByEmail("ghost@example.com")).thenReturn(null);
        ChangePasswordRequest request = ChangePasswordRequest.builder()
                .currentPassword("oldPass").newPassword("newPass123").build();
        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer old-access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
        verify(userService, never()).changePassword(anyLong(), anyString(), anyString());
    }

    @Test
    @WithMockUser(username = "test@example.com")
    void changePassword_ShortNewPassword_ReturnsBadRequest() throws Exception {
        ChangePasswordRequest request = ChangePasswordRequest.builder()
                .currentPassword("oldPass").newPassword("abc").build();

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer old-access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(userService);
    }
}
