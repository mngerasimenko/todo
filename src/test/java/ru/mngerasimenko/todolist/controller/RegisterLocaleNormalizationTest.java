package ru.mngerasimenko.todolist.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.mngerasimenko.todolist.config.TestSecurityConfig;
import ru.mngerasimenko.todolist.dto.UserDto;
import ru.mngerasimenko.todolist.dto.UserResponse;
import ru.mngerasimenko.todolist.mapper.UserMapper;
import ru.mngerasimenko.todolist.security.ApiSecurityConfig;
import ru.mngerasimenko.todolist.service.RefreshTokenService;
import ru.mngerasimenko.todolist.service.TokenBlacklistService;
import ru.mngerasimenko.todolist.service.UserService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Регрессия наряда 276: регистрация с Android 13+ падала с HTTP 400 ещё до контроллера.
 *
 * <p>{@code Locale.getDefault().toLanguageTag()} на устройстве с кастомными региональными
 * настройками возвращает тег с Unicode-расширениями ({@code ru-RU-u-fw-mon-ms-metric-mu-celsius},
 * 35 символов), а {@code @Size(max = 8)} на {@code RegisterRequest.locale} отклонял его
 * в bean validation — поэтому в логах не было даже «Попытка регистрации».
 *
 * <p>Отдельный класс, а не методы в {@link AuthControllerTest}: здесь собрана регрессия наряда 276 —
 * нормализация явного {@code locale} и её согласованность с разбором {@code Accept-Language} из PR #124.
 */
@WebMvcTest(AuthController.class)
@Import({ApiSecurityConfig.class, TestSecurityConfig.class})
class RegisterLocaleNormalizationTest {

    /** Тег из боевого лога — строка, на которой пользователь добил себя до rate-limit. */
    private static final String ANDROID_13_TAG = "ru-RU-u-fw-mon-ms-metric-mu-celsius";

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

    @BeforeEach
    void setUp() {
        UserDto created = new UserDto();
        created.setId(1L);
        created.setEmail("new@mail.ru");
        created.setName("newuser");

        when(userService.createUser(any(UserDto.class))).thenReturn(created);
        when(userMapper.toResponse(any(UserDto.class))).thenReturn(new UserResponse());
        when(refreshTokenService.createRefreshToken(anyLong())).thenReturn("refresh-token");
    }

    private static String registerJson(String locale) {
        return "{\"email\":\"new@mail.ru\",\"name\":\"newuser\",\"password\":\"password123\","
                + "\"locale\":\"" + locale + "\"}";
    }

    private String capturedLocale() {
        ArgumentCaptor<UserDto> captor = ArgumentCaptor.forClass(UserDto.class);
        verify(userService).createUser(captor.capture());
        return captor.getValue().getPreferredEmailLocale();
    }

    @Test
    @DisplayName("боевой тег из лога больше не даёт 400 и сохраняется как ru-RU")
    void register_WithAndroid13ExtendedTag_Returns201AndStoresShortTag() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(ANDROID_13_TAG)))
                .andExpect(status().isCreated());

        assertThat(capturedLocale()).isEqualTo("ru-RU");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> сохраняем \"{1}\"")
    @CsvSource({
            "ru,                                    ru",
            "ru-RU,                                 ru-RU",
            "en-US,                                 en-US",
            "ru-RU-u-fw-mon-ms-metric-mu-celsius,   ru-RU",
            "en-US-u-ca-gregory-nu-latn,            en-US",
            "zh-Hans-CN,                            zh-CN",
            "ar-SA-u-ca-islamic-umalqura-fw-sat-ms-ussystem-mu-fahrenhe-nu-arab, ar-SA",
    })
    @DisplayName("спека наряда: вход клиента → что уходит в preferredEmailLocale")
    void register_LocaleSpecTable(String sent, String stored) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(sent)))
                .andExpect(status().isCreated());

        assertThat(capturedLocale()).isEqualTo(stored);
    }

    /**
     * Одно правило укорачивания: тег из тела запроса (сеттер DTO) и тот же тег из
     * {@code Accept-Language} ({@code AuthController.resolveEmailLocale}) обязаны лечь в колонку
     * одинаково. Раньше заголовочный путь давал нижний регистр и обрезку до языка:
     * {@code zh-Hant-TW} в теле → {@code zh-TW}, в заголовке → {@code zh}.
     * <p>
     * Правило держится для тегов, которые принимает {@code AcceptLanguageParser} (well-formed,
     * язык из 2–3 букв, не длиннее 35 символов), кроме тегов с языком {@code und}: нормализатор
     * их не распознаёт. Более длинный тег в заголовке игнорируется —
     * см. {@link #register_TagLongerThanHeaderParserLimit_OnlyBodyIsNormalized()}.
     */
    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {"zh-Hant-TW", "en-us", "PT-br", ANDROID_13_TAG})
    @DisplayName("один тег в теле и в Accept-Language сохраняется одинаково")
    void register_SameTagInBodyAndHeader_StoredIdentically(String tag) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(tag)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Accept-Language", tag)
                        .content("{\"email\":\"new@mail.ru\",\"name\":\"newuser\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<UserDto> captor = ArgumentCaptor.forClass(UserDto.class);
        verify(userService, times(2)).createUser(captor.capture());
        assertThat(captor.getAllValues().get(1).getPreferredEmailLocale())
                .as("Accept-Language против тела запроса")
                .isEqualTo(captor.getAllValues().get(0).getPreferredEmailLocale());
    }

    @Test
    @DisplayName("тег длиннее предела разбора заголовка: тело нормализуется, заголовок игнорируется")
    void register_TagLongerThanHeaderParserLimit_OnlyBodyIsNormalized() throws Exception {
        // AcceptLanguageParser (PR #124) не берёт теги длиннее 35 символов, а нормализатор тела
        // разбирает и их. Расхождение осознанное: браузеры не кладут Unicode-расширения
        // в Accept-Language, а Android этот заголовок не шлёт вовсе.
        String tag = "en-US-u-ca-gregory-fw-mon-mu-celsius";
        assertThat(tag).hasSizeGreaterThan(35);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(tag)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Accept-Language", tag)
                        .content("{\"email\":\"new@mail.ru\",\"name\":\"newuser\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<UserDto> captor = ArgumentCaptor.forClass(UserDto.class);
        verify(userService, times(2)).createUser(captor.capture());
        assertThat(captor.getAllValues().get(0).getPreferredEmailLocale()).isEqualTo("en-US");
        assertThat(captor.getAllValues().get(1).getPreferredEmailLocale()).isEqualTo("ru");
    }

    @Test
    @DisplayName("обратная совместимость: клиент без поля locale регистрируется как раньше")
    void register_WithoutLocale_StillWorks() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@mail.ru\",\"name\":\"newuser\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated());

        assertThat(capturedLocale()).isEqualTo("ru");
    }

    @Test
    @DisplayName("мусор в locale по-прежнему отклоняется с 400, до создания пользователя")
    void register_WithGarbageLocale_Returns400() throws Exception {
        for (String garbage : new String[]{"123", "*", "abcdefghij", "ru_RU"}) {
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(registerJson(garbage)))
                    .andExpect(status().isBadRequest());
        }

        verify(userService, never()).createUser(any(UserDto.class));
    }
}
