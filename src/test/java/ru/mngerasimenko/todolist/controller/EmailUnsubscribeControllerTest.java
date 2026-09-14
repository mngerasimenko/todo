package ru.mngerasimenko.todolist.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.mngerasimenko.todolist.config.TestSecurityConfig;
import ru.mngerasimenko.todolist.exception.UserNotFoundException;
import ru.mngerasimenko.todolist.model.User;
import ru.mngerasimenko.todolist.security.ApiSecurityConfig;
import ru.mngerasimenko.todolist.service.MessageService;
import ru.mngerasimenko.todolist.service.UserService;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Тесты для EmailUnsubscribeController.
 * Открытый endpoint без авторизации — путь permitAll'd в {@link ApiSecurityConfig},
 * JWT-зависимости мокируются через {@link TestSecurityConfig} (паттерн как в AuthControllerTest).
 */
@WebMvcTest(EmailUnsubscribeController.class)
@Import({ApiSecurityConfig.class, TestSecurityConfig.class})
class EmailUnsubscribeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private MessageService messageService;

    /** Нужен для прямых вызовов package-private resolveLocaleFromHeader в обход MockMvc. */
    @Autowired
    private EmailUnsubscribeController controller;

    @BeforeEach
    void setUp() {
        // MessageService возвращает "stub-{key}" — позволяет grep'ать ключи в HTML
        // и понимать, какие сообщения попали в страницу.
        when(messageService.getMessage(anyString(), any(Locale.class), (Object[]) any()))
                .thenAnswer(inv -> "stub-" + inv.getArgument(0));
        when(messageService.getMessage(anyString(), any(Locale.class)))
                .thenAnswer(inv -> "stub-" + inv.getArgument(0));
    }

    @Test
    void unsubscribe_ValidToken_RendersSuccessPage() throws Exception {
        when(userService.unsubscribeFromReminders("good-token")).thenReturn("ru");

        mockMvc.perform(get("/api/users/unsubscribe-reminder").param("token", "good-token"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("stub-unsubscribe.success.title")))
                .andExpect(content().string(containsString("stub-unsubscribe.success.body")))
                .andExpect(content().string(containsString("lang=\"ru\"")));

        verify(userService).unsubscribeFromReminders("good-token");
    }

    @Test
    void unsubscribe_ValidTokenEnLocale_RendersEnglishSuccessPage() throws Exception {
        // preferred_email_locale пользователя = "en" → success-страница на английском
        when(userService.unsubscribeFromReminders("good-token-en")).thenReturn("en");

        mockMvc.perform(get("/api/users/unsubscribe-reminder").param("token", "good-token-en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("lang=\"en\"")));
    }

    @Test
    void unsubscribe_ValidTokenRegionalEnLocale_RendersEnglishSuccessPage() throws Exception {
        // "en-US" из БД — тот же английский: язык страницы берётся по primary subtag.
        when(userService.unsubscribeFromReminders("good-token-en-us")).thenReturn("en-US");

        mockMvc.perform(get("/api/users/unsubscribe-reminder").param("token", "good-token-en-us"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("lang=\"en\"")));
    }

    @Test
    void unsubscribe_ValidTokenUppercaseLocale_RendersEnglishSuccessPage() throws Exception {
        // Явный locale клиента уходит в БД как прислан (LocaleValidation.PATTERN_OPTIONAL
        // разрешает верхний регистр), поэтому в колонке лежит и "EN". Письмо такой юзер
        // получает английское — страница отписки обязана совпасть с ним, а не разойтись.
        when(userService.unsubscribeFromReminders("token-upper")).thenReturn("EN");

        mockMvc.perform(get("/api/users/unsubscribe-reminder").param("token", "token-upper"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("lang=\"en\"")));
    }

    @Test
    void unsubscribe_ValidTokenUnsupportedLocale_RendersRussianPageWithMatchingLangAttribute() throws Exception {
        // Атрибут lang обязан описывать текст, который реально на странице. Бандла messages_eng
        // нет, текст приходит русский — значит и lang должен быть ru. Прежняя проверка
        // locale.getLanguage().startsWith("en") принимала "eng" за английский и выдавала
        // <html lang="en"> вокруг русского текста: скринридер читал русский английским голосом.
        when(userService.unsubscribeFromReminders("token-eng")).thenReturn("eng");
        when(userService.unsubscribeFromReminders("token-de")).thenReturn("de");

        mockMvc.perform(get("/api/users/unsubscribe-reminder").param("token", "token-eng"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("lang=\"ru\"")));
        mockMvc.perform(get("/api/users/unsubscribe-reminder").param("token", "token-de"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("lang=\"ru\"")));
    }

    @Test
    void unsubscribe_BlankToken_RendersAlreadyUsedPage() throws Exception {
        when(userService.unsubscribeFromReminders((String) isNull()))
                .thenThrow(new UserNotFoundException("Unsubscribe token is required"));

        mockMvc.perform(get("/api/users/unsubscribe-reminder"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("stub-unsubscribe.invalid.title")))
                .andExpect(content().string(containsString("stub-unsubscribe.invalid.body")));
    }

    @Test
    void unsubscribe_InvalidToken_RendersAlreadyUsedPage() throws Exception {
        when(userService.unsubscribeFromReminders("bad-token"))
                .thenThrow(new UserNotFoundException("Invalid or expired unsubscribe token"));

        mockMvc.perform(get("/api/users/unsubscribe-reminder").param("token", "bad-token"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("stub-unsubscribe.invalid.title")));

        verify(userService).unsubscribeFromReminders("bad-token");
    }

    @Test
    void unsubscribe_ConcurrentRace_RendersAlreadyUsedPage() throws Exception {
        // Two concurrent hits — second one fails optimistic locking, sees the same neutral page.
        when(userService.unsubscribeFromReminders("racy-token"))
                .thenThrow(new ObjectOptimisticLockingFailureException(User.class, 1L));

        mockMvc.perform(get("/api/users/unsubscribe-reminder").param("token", "racy-token"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("stub-unsubscribe.invalid.title")));

        verify(userService).unsubscribeFromReminders("racy-token");
    }

    @Test
    void unsubscribe_InvalidTokenAcceptLanguageEn_RendersEnglishAlreadyUsedPage() throws Exception {
        // Юзер не найден → locale из Accept-Language header
        when(userService.unsubscribeFromReminders("bad-token"))
                .thenThrow(new UserNotFoundException("Invalid or expired unsubscribe token"));

        mockMvc.perform(get("/api/users/unsubscribe-reminder")
                        .param("token", "bad-token")
                        .header("Accept-Language", "en-US,en;q=0.9"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("lang=\"en\"")));
    }

    @Test
    void unsubscribe_InvalidTokenAcceptLanguageByQuality_PicksHighestWeightedLanguage() throws Exception {
        // Клиент прямо сказал, что английский ему приятнее русского. Разбор по первому
        // элементу списка это игнорировал и отдавал русскую страницу.
        when(userService.unsubscribeFromReminders("bad-token"))
                .thenThrow(new UserNotFoundException("Invalid or expired unsubscribe token"));

        mockMvc.perform(get("/api/users/unsubscribe-reminder")
                        .param("token", "bad-token")
                        .header("Accept-Language", "ru;q=0.1,en;q=0.9"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("lang=\"en\"")));
    }

    @Test
    void unsubscribe_InvalidTokenWeakEnglishBeforeImplicitRussian_PicksRussian() throws Exception {
        // Единственный вход, на котором прежний разбор («первый элемент списка») и новый
        // расходятся не в пользу нового по интуиции, но в пользу RFC: у "ru" без параметра
        // вес 1.0, и он обыгрывает явно ослабленный английский. Пинним осознанно.
        when(userService.unsubscribeFromReminders("bad-token"))
                .thenThrow(new UserNotFoundException("Invalid or expired unsubscribe token"));

        mockMvc.perform(get("/api/users/unsubscribe-reminder")
                        .param("token", "bad-token")
                        .header("Accept-Language", "en;q=0.9,ru"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("lang=\"ru\"")));
    }

    @Test
    void unsubscribe_InvalidTokenUnsupportedPreferredLanguage_FallsBackToSupportedOne() throws Exception {
        // Главный практический выигрыш: немецкому клиенту, который английский всё же принимает,
        // прежний разбор отдавал русскую страницу — он смотрел только на первый элемент.
        when(userService.unsubscribeFromReminders("bad-token"))
                .thenThrow(new UserNotFoundException("Invalid or expired unsubscribe token"));

        mockMvc.perform(get("/api/users/unsubscribe-reminder")
                        .param("token", "bad-token")
                        .header("Accept-Language", "de-DE,de;q=0.9,en;q=0.8"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("lang=\"en\"")));
    }

    @Test
    void unsubscribe_InvalidTokenUnacceptableLanguage_FallsBackToRu() throws Exception {
        // q=0 по RFC 9110 означает «неприемлемо» — такой язык выбирать нельзя.
        when(userService.unsubscribeFromReminders("bad-token"))
                .thenThrow(new UserNotFoundException("Invalid or expired unsubscribe token"));

        mockMvc.perform(get("/api/users/unsubscribe-reminder")
                        .param("token", "bad-token")
                        .header("Accept-Language", "en;q=0"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("lang=\"ru\"")));
    }

    /**
     * Эндпоинт открытый (permitAll), Accept-Language приходит произвольный.
     * <p>
     * Разбор здесь и раньше не падал на «-» (был ручной split), поэтому первые строки —
     * защита от регрессии при переходе на общий парсер. Настоящие изменения поведения —
     * в последних трёх: битый элемент рядом с валидным больше не заслоняет язык,
     * а «eng»/«en_US» перестали считаться английским (прежний {@code startsWith("en")}
     * принимал и их).
     * <p>
     * Проверяется прямым вызовом, а не через MockMvc: такой заголовок роняет сам
     * {@code MockHttpServletRequest.addHeader} (он парсит Accept-Language через
     * {@code Locale.LanguageRange.parse}, а тот бросает на «-» ArrayIndexOutOfBoundsException),
     * поэтому до контроллера в MockMvc он не долетает.
     */
    @Test
    void resolveLocaleFromHeader_MalformedAcceptLanguage_FallsBackToRu() {
        assertThat(controller.resolveLocaleFromHeader("-")).isEqualTo(new Locale("ru"));
        assertThat(controller.resolveLocaleFromHeader("-,en")).isEqualTo(Locale.ENGLISH);
        assertThat(controller.resolveLocaleFromHeader("-".repeat(8000))).isEqualTo(new Locale("ru"));
        assertThat(controller.resolveLocaleFromHeader(null)).isEqualTo(new Locale("ru"));
        // "eng"/"english" — не английский по BCP-47; прежний startsWith("en") их принимал.
        assertThat(controller.resolveLocaleFromHeader("eng")).isEqualTo(new Locale("ru"));
        assertThat(controller.resolveLocaleFromHeader("en_US")).isEqualTo(new Locale("ru"));
    }

    @Test
    void unsubscribe_HugeAcceptLanguage_RendersPageInsteadOfFailing() throws Exception {
        // Tomcat пропускает заголовок до 8 КБ. Тест характеризационный — прежний split его тоже
        // переживал; он фиксирует, что переход на общий парсер этого не сломал.
        StringBuilder header = new StringBuilder();
        for (int i = 0; header.length() < 8000; i++) {
            header.append("qa").append((char) ('a' + i % 26)).append("-x").append(i).append(";q=0.5,");
        }
        header.append("zz");

        when(userService.unsubscribeFromReminders("bad-token"))
                .thenThrow(new UserNotFoundException("Invalid or expired unsubscribe token"));

        mockMvc.perform(get("/api/users/unsubscribe-reminder")
                        .param("token", "bad-token")
                        .header("Accept-Language", header.toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("lang=\"ru\"")));
    }

    // ===== scope=todo_due (Task 7) =====

    @Test
    void unsubscribe_ScopeTodoDue_CallsUnsubscribeFromTodoRemindersOnly() throws Exception {
        when(userService.unsubscribeFromTodoReminders("good-token")).thenReturn("ru");

        mockMvc.perform(get("/api/users/unsubscribe-reminder")
                        .param("token", "good-token")
                        .param("scope", "todo_due"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("stub-unsubscribe.success.title")))
                .andExpect(content().string(containsString("lang=\"ru\"")));

        verify(userService).unsubscribeFromTodoReminders("good-token");
        verify(userService, never()).unsubscribeFromReminders(anyString());
    }

    @Test
    void unsubscribe_NoScope_CallsUnsubscribeFromRemindersOnly() throws Exception {
        when(userService.unsubscribeFromReminders("good-token")).thenReturn("ru");

        mockMvc.perform(get("/api/users/unsubscribe-reminder").param("token", "good-token"))
                .andExpect(status().isOk());

        verify(userService).unsubscribeFromReminders("good-token");
        verify(userService, never()).unsubscribeFromTodoReminders(anyString());
    }

    @Test
    void unsubscribe_UnknownScope_FallsBackToUnsubscribeFromReminders() throws Exception {
        // scope прилетает из URL, который юзер может редактировать руками — незнакомое
        // значение не должно ни падать, ни расширять то, что выключается (не оба флага сразу).
        when(userService.unsubscribeFromReminders("good-token")).thenReturn("ru");

        mockMvc.perform(get("/api/users/unsubscribe-reminder")
                        .param("token", "good-token")
                        .param("scope", "whatever"))
                .andExpect(status().isOk());

        verify(userService).unsubscribeFromReminders("good-token");
        verify(userService, never()).unsubscribeFromTodoReminders(anyString());
    }
}
