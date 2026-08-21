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
