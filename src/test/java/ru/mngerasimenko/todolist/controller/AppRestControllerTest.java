package ru.mngerasimenko.todolist.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import ru.mngerasimenko.todolist.config.TestSecurityConfig;
import ru.mngerasimenko.todolist.security.ApiSecurityConfig;
import ru.mngerasimenko.todolist.service.EmailService;
import ru.mngerasimenko.todolist.service.PushNotificationService;
import ru.mngerasimenko.todolist.service.RedisHealthService;
import ru.mngerasimenko.todolist.settings.AppProperties;
import ru.mngerasimenko.todolist.settings.Constants;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;


@WebMvcTest(AppRestController.class)
@Import({ApiSecurityConfig.class, TestSecurityConfig.class})
@EnableConfigurationProperties(AppProperties.class)
class AppRestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EmailService emailService;

    @MockitoBean
    private PushNotificationService pushNotificationService;

    @MockitoBean
    private RedisHealthService redisHealthService;

    @Test
    void getStatus_ReturnsOkWithStatusAndVersion() throws Exception {
        mockMvc.perform(get("/api/status")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(true))
                .andExpect(jsonPath("$.version").value("0.0.1"))
                .andExpect(jsonPath("$.min_android_version").value(1))
                .andExpect(jsonPath("$.appName").doesNotExist());
    }

    @Test
    void getStatus_SmtpHealthyTrue_ReturnsTrue() throws Exception {
        when(emailService.isSmtpHealthy()).thenReturn(true);

        mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.smtp_healthy").value(true));
    }

    @Test
    void getStatus_SmtpHealthyFalse_ReturnsFalse() throws Exception {
        when(emailService.isSmtpHealthy()).thenReturn(false);

        mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.smtp_healthy").value(false));
    }

    @Test
    void getStatus_ResponseContainsOnlyExpectedFields() throws Exception {
        mockMvc.perform(get("/api/status")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.version").exists())
                .andExpect(jsonPath("$.min_android_version").exists())
                .andExpect(jsonPath("$.appName").doesNotExist())
                .andExpect(jsonPath("$.timestamp").doesNotExist());
    }

    @Test
    void getStatus_FirebaseHealthyTrue_ReturnsTrue() throws Exception {
        when(pushNotificationService.isFirebaseHealthy()).thenReturn(true);

        mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firebase_healthy").value(true));
    }

    @Test
    void getStatus_FirebaseHealthyFalse_ReturnsFalse() throws Exception {
        when(pushNotificationService.isFirebaseHealthy()).thenReturn(false);

        mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firebase_healthy").value(false));
    }

    @Test
    void getStatus_RedisHealthyTrue_ReturnsTrue() throws Exception {
        when(redisHealthService.isRedisHealthy()).thenReturn(true);

        mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redis_healthy").value(true));
    }

    @Test
    void getStatus_RedisHealthyFalse_ReturnsFalse() throws Exception {
        when(redisHealthService.isRedisHealthy()).thenReturn(false);

        mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redis_healthy").value(false));
    }

    @Test
    void getAppName_ReturnsOkWithAppName() throws Exception {
        mockMvc.perform(get("/api/appName")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.appName").value(Constants.APP_NAME))
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist());
    }

    @Test
    void getAppName_AppNameMatchesConstant() throws Exception {
        mockMvc.perform(get("/api/appName")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.appName").value(Constants.APP_NAME));
    }

    @Test
    void getStatus_ContentTypeIsJson() throws Exception {
        mockMvc.perform(get("/api/status"))
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_JSON_VALUE));
    }

    @Test
    void getAppName_ContentTypeIsJson() throws Exception {
        mockMvc.perform(get("/api/appName"))
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_JSON_VALUE));
    }

    @Test
    void bothEndpoints_ReturnValidResponses() throws Exception {
        mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(true));

        mockMvc.perform(get("/api/appName"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appName").value(Constants.APP_NAME));
    }
}
