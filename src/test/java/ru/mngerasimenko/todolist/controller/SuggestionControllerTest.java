package ru.mngerasimenko.todolist.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.mngerasimenko.todolist.config.SuperAdminProperties;
import ru.mngerasimenko.todolist.config.TestSecurityConfig;
import ru.mngerasimenko.todolist.dto.SuggestionResponse;
import ru.mngerasimenko.todolist.security.ApiSecurityConfig;
import ru.mngerasimenko.todolist.service.SuggestionService;
import ru.mngerasimenko.todolist.settings.SuggestionProperties;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice-тесты публичного эндпоинта {@code GET /api/suggestions}.
 * Проверяют что эндпоинт доступен без JWT, что параметры корректно дефолтятся,
 * что bound-валидация limit работает.
 */
@WebMvcTest(SuggestionController.class)
@Import({ApiSecurityConfig.class, TestSecurityConfig.class,
        ru.mngerasimenko.todolist.security.SuperAdminGuard.class})
class SuggestionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SuggestionService suggestionService;

    @MockitoBean
    private SuggestionProperties suggestionProperties;

    @MockitoBean
    private SuperAdminProperties superAdminProperties;

    @MockitoBean
    private ru.mngerasimenko.todolist.featureflags.FeatureFlagStore flagStore;

    @Test
    void suggest_NoAuth_Returns200() throws Exception {
        when(suggestionProperties.getDefaultLimit()).thenReturn(5);
        when(suggestionService.suggest(anyString(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/suggestions").param("prefix", "хле"))
                .andExpect(status().isOk());
    }

    @Test
    void suggest_ReturnsListOfText() throws Exception {
        when(suggestionProperties.getDefaultLimit()).thenReturn(5);
        when(suggestionService.suggest("молок", 5)).thenReturn(List.of(
                SuggestionResponse.builder().text("Молоко").build(),
                SuggestionResponse.builder().text("Молочка").build()
        ));

        mockMvc.perform(get("/api/suggestions")
                        .param("prefix", "молок")
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$[0].text").value("Молоко"))
                .andExpect(jsonPath("$[1].text").value("Молочка"));

        verify(suggestionService).suggest("молок", 5);
    }

    @Test
    void suggest_NoLimit_UsesDefault() throws Exception {
        when(suggestionProperties.getDefaultLimit()).thenReturn(7);
        when(suggestionService.suggest(anyString(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/suggestions").param("prefix", "хле"))
                .andExpect(status().isOk());

        verify(suggestionService).suggest(eq("хле"), eq(7));
    }

    @Test
    void suggest_LimitBelowMin_Returns400() throws Exception {
        mockMvc.perform(get("/api/suggestions").param("prefix", "хле").param("limit", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void suggest_LimitAboveMax_Returns400() throws Exception {
        mockMvc.perform(get("/api/suggestions").param("prefix", "хле").param("limit", "100"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void suggest_NoPrefix_StillReturns200WithEmptyDefault() throws Exception {
        when(suggestionProperties.getDefaultLimit()).thenReturn(5);
        when(suggestionService.suggest("", 5)).thenReturn(List.of());

        mockMvc.perform(get("/api/suggestions"))
                .andExpect(status().isOk());

        verify(suggestionService).suggest("", 5);
    }
}
