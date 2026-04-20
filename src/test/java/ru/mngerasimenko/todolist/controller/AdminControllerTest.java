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
import ru.mngerasimenko.todolist.security.ApiSecurityConfig;
import ru.mngerasimenko.todolist.security.SuperAdminGuard;
import ru.mngerasimenko.todolist.service.AdminService;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
}
