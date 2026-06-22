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
import ru.mngerasimenko.todolist.service.AdminService;
import ru.mngerasimenko.todolist.service.SuggestionService;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
    void setFlag_knownName_Returns204AndCallsStore() throws Exception {
        mockMvc.perform(put("/api/admin/flags/{name}/{value}",
                        FeatureFlag.RATE_LIMIT.getName(), "false"))
                .andExpect(status().isNoContent());

        verify(flagStore).set(FeatureFlag.RATE_LIMIT, false);
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL)
    void setFlag_unknownName_Returns404() throws Exception {
        mockMvc.perform(put("/api/admin/flags/{name}/{value}", "unknown.flag", "true"))
                .andExpect(status().isNotFound());

        verify(flagStore, never()).set(eq(FeatureFlag.RATE_LIMIT), org.mockito.ArgumentMatchers.anyBoolean());
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
    void resetFlag_knownName_Returns204AndCallsStore() throws Exception {
        mockMvc.perform(delete("/api/admin/flags/{name}", FeatureFlag.PUSH_NOTIFICATIONS.getName()))
                .andExpect(status().isNoContent());

        verify(flagStore).reset(FeatureFlag.PUSH_NOTIFICATIONS);
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
}
