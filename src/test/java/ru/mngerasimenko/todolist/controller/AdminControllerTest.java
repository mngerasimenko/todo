package ru.mngerasimenko.todolist.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import ru.mngerasimenko.todolist.config.SuperAdminProperties;
import ru.mngerasimenko.todolist.config.TestSecurityConfig;
import ru.mngerasimenko.todolist.dto.admin.InactiveReminderTriggerResponse;
import ru.mngerasimenko.todolist.exception.UserNotFoundException;
import ru.mngerasimenko.todolist.featureflags.FeatureFlag;
import ru.mngerasimenko.todolist.featureflags.FeatureFlagStore;
import ru.mngerasimenko.todolist.featureflags.FlagSource;
import ru.mngerasimenko.todolist.security.ApiSecurityConfig;
import ru.mngerasimenko.todolist.security.SuperAdminGuard;
import ru.mngerasimenko.todolist.dto.admin.SuggestionReseedReport;
import ru.mngerasimenko.todolist.service.AdminService;
import ru.mngerasimenko.todolist.service.SuggestionReseedService;
import ru.mngerasimenko.todolist.service.SuggestionService;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminController.class)
@Import({ApiSecurityConfig.class, TestSecurityConfig.class, SuperAdminGuard.class})
class AdminControllerTest {

    private static final String ADMIN_EMAIL = "admin@todolist.ru";
    private static final String TARGET_EMAIL = "target@mail.ru";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminService adminService;

    @MockitoBean
    private SuperAdminProperties superAdminProperties;

    @MockitoBean
    private FeatureFlagStore flagStore;

    @MockitoBean
    private SuggestionService suggestionService;

    @MockitoBean
    private SuggestionReseedService suggestionReseedService;

    @BeforeEach
    void setUp() {
        when(superAdminProperties.getEmails()).thenReturn(List.of(ADMIN_EMAIL));
    }

    @Test
    void triggerInactiveReminder_withoutAuth_Returns401() throws Exception {
        mockMvc.perform(post("/api/admin/users/{email}/inactive-reminder", TARGET_EMAIL))
                .andExpect(status().isUnauthorized());

        verify(adminService, never()).triggerInactiveReminder(TARGET_EMAIL);
    }

    @Test
    @WithMockUser(username = "regular@mail.ru")
    void triggerInactiveReminder_regularUser_Returns404() throws Exception {
        // accessDeniedHandler скрывает /api/admin/** для не супер-админов как 404
        mockMvc.perform(post("/api/admin/users/{email}/inactive-reminder", TARGET_EMAIL))
                .andExpect(status().isNotFound());

        verify(adminService, never()).triggerInactiveReminder(TARGET_EMAIL);
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL)
    void triggerInactiveReminder_superAdmin_Returns200WithStats() throws Exception {
        when(adminService.triggerInactiveReminder(TARGET_EMAIL))
                .thenReturn(InactiveReminderTriggerResponse.builder()
                        .userId(42L)
                        .pushSent(true)
                        .emailSent(true)
                        .build());

        mockMvc.perform(post("/api/admin/users/{email}/inactive-reminder", TARGET_EMAIL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value(42))
                .andExpect(jsonPath("$.push_sent").value(true))
                .andExpect(jsonPath("$.email_sent").value(true));

        verify(adminService).triggerInactiveReminder(TARGET_EMAIL);
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL)
    void triggerInactiveReminder_userNotFound_Returns404() throws Exception {
        when(adminService.triggerInactiveReminder(TARGET_EMAIL))
                .thenThrow(new UserNotFoundException("не найден"));

        mockMvc.perform(post("/api/admin/users/{email}/inactive-reminder", TARGET_EMAIL))
                .andExpect(status().isNotFound());

        verify(adminService).triggerInactiveReminder(eq(TARGET_EMAIL));
    }

    @Test
    @WithMockUser(username = "ADMIN@TODOLIST.RU")
    void triggerInactiveReminder_superAdminUppercaseEmail_CaseInsensitiveMatch() throws Exception {
        when(adminService.triggerInactiveReminder(TARGET_EMAIL))
                .thenReturn(InactiveReminderTriggerResponse.builder()
                        .userId(42L).pushSent(false).emailSent(true).build());

        mockMvc.perform(post("/api/admin/users/{email}/inactive-reminder", TARGET_EMAIL))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL)
    void triggerInactiveReminder_emailWithDots_PathParsedCorrectly() throws Exception {
        String dotEmail = "foo.bar@sub.example.co.uk";
        when(adminService.triggerInactiveReminder(dotEmail))
                .thenReturn(InactiveReminderTriggerResponse.builder()
                        .userId(7L).pushSent(true).emailSent(true).build());

        mockMvc.perform(post("/api/admin/users/{email}/inactive-reminder", dotEmail))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value(7));

        verify(adminService).triggerInactiveReminder(dotEmail);
    }

    // ===== Feature flags =====

    @Test
    void listFlags_withoutAuth_Returns401() throws Exception {
        mockMvc.perform(get("/api/admin/flags"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "regular@mail.ru")
    void listFlags_regularUser_Returns404() throws Exception {
        mockMvc.perform(get("/api/admin/flags"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL)
    void listFlags_superAdmin_ReturnsAllFlagsWithSource() throws Exception {
        Map<FeatureFlag, FeatureFlagStore.Resolution> snapshot = new EnumMap<>(FeatureFlag.class);
        snapshot.put(FeatureFlag.RATE_LIMIT,
                new FeatureFlagStore.Resolution(true, FlagSource.DEFAULT));
        snapshot.put(FeatureFlag.INACTIVE_REMINDER,
                new FeatureFlagStore.Resolution(false, FlagSource.RUNTIME));
        snapshot.put(FeatureFlag.PUSH_NOTIFICATIONS,
                new FeatureFlagStore.Resolution(true, FlagSource.ENV));
        when(flagStore.snapshot()).thenReturn(snapshot);

        mockMvc.perform(get("/api/admin/flags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(3)))
                .andExpect(jsonPath("$[?(@.name == 'rate-limit.enabled')].source")
                        .value("DEFAULT"))
                .andExpect(jsonPath("$[?(@.name == 'app.inactive-reminder.enabled')].source")
                        .value("RUNTIME"))
                .andExpect(jsonPath("$[?(@.name == 'app.inactive-reminder.enabled')].enabled")
                        .value(false));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL)
    void setFlag_knownName_ReportsWhetherItWillSurviveARestart() throws Exception {
        // Процессный флаг: переключение действует, но рестарт его снимет — и это видно в ответе.
        when(flagStore.set(eq(FeatureFlag.RATE_LIMIT), eq(false), anyString())).thenReturn(false);

        mockMvc.perform(put("/api/admin/flags/{name}/{value}",
                        FeatureFlag.RATE_LIMIT.getName(), "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(FeatureFlag.RATE_LIMIT.getName()))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.persisted").value(false))
                // Без класса долговечности false нельзя истолковать: процессный флаг или
                // упавшая запись — во время инцидента это разные ситуации.
                .andExpect(jsonPath("$.override_lifetime").value("PROCESS"));

        // Автор переключения уходит в БД вместе со значением — проверяем КОНКРЕТНЫЙ email,
        // а не anyString(): подстановка "anonymous" проходила мимо проверки и обесценивала аудит.
        verify(flagStore).set(FeatureFlag.RATE_LIMIT, false, ADMIN_EMAIL);
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL)
    void setFlag_featureFlag_reportsPersistedTrue() throws Exception {
        // Флаг фичи: переключение сохранено, деплой его не отменит.
        when(flagStore.set(eq(FeatureFlag.SUGGESTIONS), eq(false), anyString())).thenReturn(true);

        mockMvc.perform(put("/api/admin/flags/{name}/{value}",
                        FeatureFlag.SUGGESTIONS.getName(), "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.persisted").value(true))
                .andExpect(jsonPath("$.override_lifetime").value("PERSISTENT"));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL)
    void setFlag_unknownName_Returns404() throws Exception {
        mockMvc.perform(put("/api/admin/flags/{name}/{value}", "unknown.flag", "true"))
                .andExpect(status().isNotFound());

        verify(flagStore, never()).set(any(), org.mockito.ArgumentMatchers.anyBoolean(), anyString());
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL)
    void setFlag_invalidBooleanValue_Returns400() throws Exception {
        mockMvc.perform(put("/api/admin/flags/{name}/{value}",
                        FeatureFlag.RATE_LIMIT.getName(), "notabool"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL)
    void listFlags_showWhetherAnOverrideSurvivesARestart() throws Exception {
        // Главный вопрос инцидента — «вернётся ли фича сама». Без этих полей оператор не может
        // ответить на него по пульту, и их удаление раньше не ломало ни одного теста.
        Map<FeatureFlag, FeatureFlagStore.Resolution> snapshot = new EnumMap<>(FeatureFlag.class);
        snapshot.put(FeatureFlag.RATE_LIMIT, new FeatureFlagStore.Resolution(false, FlagSource.RUNTIME));
        snapshot.put(FeatureFlag.CLIENT_SUGGESTIONS_DEDUP,
                new FeatureFlagStore.Resolution(false, FlagSource.PERSISTED));
        when(flagStore.snapshot()).thenReturn(snapshot);

        mockMvc.perform(get("/api/admin/flags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='rate-limit.enabled')].override_lifetime").value("PROCESS"))
                .andExpect(jsonPath("$[?(@.name=='rate-limit.enabled')].source").value("RUNTIME"))
                .andExpect(jsonPath("$[?(@.name=='client.suggestions.dedup.enabled')].override_lifetime")
                        .value("PERSISTENT"))
                .andExpect(jsonPath("$[?(@.name=='client.suggestions.dedup.enabled')].source")
                        .value("PERSISTED"))
                .andExpect(jsonPath("$[?(@.name=='client.suggestions.dedup.enabled')].audience")
                        .value("CLIENT"));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL)
    void resetFlag_knownName_ReportsThatTheOverrideWasCleared() throws Exception {
        when(flagStore.reset(FeatureFlag.PUSH_NOTIFICATIONS)).thenReturn(true);
        when(flagStore.isEnabled(FeatureFlag.PUSH_NOTIFICATIONS)).thenReturn(true);

        mockMvc.perform(delete("/api/admin/flags/{name}", FeatureFlag.PUSH_NOTIFICATIONS.getName()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cleared").value(true))
                .andExpect(jsonPath("$.override_lifetime").value("PERSISTENT"))
                // doesNotHaveJsonPath, а не doesNotExist: второй проходит и для "persisted": null,
                // то есть снятие @JsonInclude(NON_NULL) осталось бы незамеченным, а в ответе
                // появилось бы пустое поле-обманка. Строгое сравнение всего тела тоже не годится —
                // оно ломалось бы от любого нового поля (changed_at уже в планах).
                .andExpect(jsonPath("$.persisted").doesNotHaveJsonPath());

        verify(flagStore).reset(FeatureFlag.PUSH_NOTIFICATIONS);
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL)
    void resetFlag_failedDeleteIsVisibleToTheAdmin() throws Exception {
        // Строку не удалили → на ближайшем рестарте флаг вернётся к сохранённому значению.
        // Молчаливое 204 здесь означало бы, что админ уходит, считая фичу включённой обратно.
        when(flagStore.reset(FeatureFlag.PUSH_NOTIFICATIONS)).thenReturn(false);
        when(flagStore.isEnabled(FeatureFlag.PUSH_NOTIFICATIONS)).thenReturn(true);

        mockMvc.perform(delete("/api/admin/flags/{name}", FeatureFlag.PUSH_NOTIFICATIONS.getName()))
                // Строка осталась в БД → прежнее значение вернётся после рестарта.
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cleared").value(false));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL)
    void resetFlag_unknownName_Returns404() throws Exception {
        mockMvc.perform(delete("/api/admin/flags/{name}", "unknown.flag"))
                .andExpect(status().isNotFound());

        verify(flagStore, never()).reset(org.mockito.ArgumentMatchers.any());
    }

    // ===== Suggestion block =====

    @Test
    void blockSuggestion_withoutAuth_Returns401() throws Exception {
        mockMvc.perform(post("/api/admin/suggestions/{text}/block", "молоко"))
                .andExpect(status().isUnauthorized());

        verify(suggestionService, never()).block(anyString());
    }

    @Test
    @WithMockUser(username = "regular@mail.ru")
    void blockSuggestion_regularUser_Returns404() throws Exception {
        mockMvc.perform(post("/api/admin/suggestions/{text}/block", "молоко"))
                .andExpect(status().isNotFound());

        verify(suggestionService, never()).block(anyString());
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL)
    void blockSuggestion_existingText_Returns204() throws Exception {
        when(suggestionService.block("молоко")).thenReturn(true);

        mockMvc.perform(post("/api/admin/suggestions/{text}/block", "молоко"))
                .andExpect(status().isNoContent());

        verify(suggestionService).block("молоко");
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL)
    void blockSuggestion_unknownText_Returns404WithStandardJsonBody() throws Exception {
        when(suggestionService.block("несуществует")).thenReturn(false);

        mockMvc.perform(post("/api/admin/suggestions/{text}/block", "несуществует"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Suggestion not found in dictionary"));

        verify(suggestionService).block("несуществует");
    }

    // ===== Suggestion reseed (029) =====

    @Test
    void reseedSuggestions_withoutAuth_Returns401() throws Exception {
        mockMvc.perform(post("/api/admin/suggestions/reseed"))
                .andExpect(status().isUnauthorized());

        verify(suggestionReseedService, never()).reseed(org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @WithMockUser(username = "regular@mail.ru")
    void reseedSuggestions_regularUser_Returns404() throws Exception {
        mockMvc.perform(post("/api/admin/suggestions/reseed"))
                .andExpect(status().isNotFound());

        verify(suggestionReseedService, never()).reseed(org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL)
    void reseedSuggestions_superAdmin_DefaultsToDryRun_Returns200WithReport() throws Exception {
        when(suggestionReseedService.reseed(true)).thenReturn(
                SuggestionReseedReport.builder()
                        .dryRun(true)
                        .productsKept(5)
                        .contributorRowsWritten(20)
                        .editorialVerbsFloored(22)
                        .nonBlockedDeleted(40)
                        .minFreqApplied(3)
                        .topSample(List.of())
                        .build());

        mockMvc.perform(post("/api/admin/suggestions/reseed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dry_run").value(true))
                .andExpect(jsonPath("$.products_kept").value(5));

        // дефолт — dryRun=true (сначала смотрим, потом применяем)
        verify(suggestionReseedService).reseed(true);
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL)
    void reseedSuggestions_dryRunFalse_AppliesAndReturnsReport() throws Exception {
        when(suggestionReseedService.reseed(false)).thenReturn(
                SuggestionReseedReport.builder()
                        .dryRun(false)
                        .productsKept(5)
                        .contributorRowsWritten(20)
                        .editorialVerbsFloored(22)
                        .nonBlockedDeleted(40)
                        .minFreqApplied(3)
                        .topSample(List.of())
                        .build());

        mockMvc.perform(post("/api/admin/suggestions/reseed").param("dryRun", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dry_run").value(false))
                .andExpect(jsonPath("$.contributor_rows_written").value(20));

        verify(suggestionReseedService).reseed(false);
    }
}
