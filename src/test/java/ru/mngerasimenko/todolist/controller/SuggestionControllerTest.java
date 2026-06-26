package ru.mngerasimenko.todolist.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import ru.mngerasimenko.todolist.config.SuperAdminProperties;
import ru.mngerasimenko.todolist.config.TestSecurityConfig;
import ru.mngerasimenko.todolist.dto.SuggestionBulkResponse;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

    // ===== GET /api/suggestions/all (bulk, Server R-7) =====

    @Test
    void all_NoAuth_Returns200WithContentAndETag() throws Exception {
        when(suggestionService.findAllVisible()).thenReturn(List.of(
                SuggestionBulkResponse.builder().text("молоко").textDisplay("Молоко").freq(9).build(),
                SuggestionBulkResponse.builder().text("масло").textDisplay("Масло").freq(3).build()
        ));

        mockMvc.perform(get("/api/suggestions/all"))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$[0].text").value("молоко"))
                .andExpect(jsonPath("$[0].textDisplay").value("Молоко"))
                .andExpect(jsonPath("$[0].freq").value(9))
                .andExpect(jsonPath("$[1].text").value("масло"));
    }

    @Test
    void all_MatchingIfNoneMatch_Returns304() throws Exception {
        when(suggestionService.findAllVisible()).thenReturn(List.of(
                SuggestionBulkResponse.builder().text("хлеб").textDisplay("Хлеб").freq(5).build()
        ));

        // Первый запрос — забираем ETag из ответа.
        String etag = mockMvc.perform(get("/api/suggestions/all"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        // Повторный с тем же ETag (словарь не менялся) → 304 без тела.
        mockMvc.perform(get("/api/suggestions/all").header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified());
    }

    @Test
    void all_EmptyDictionary_Returns200EmptyArray() throws Exception {
        when(suggestionService.findAllVisible()).thenReturn(List.of());

        mockMvc.perform(get("/api/suggestions/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));
    }

    @Test
    void all_NotModified_HasEmptyBody() throws Exception {
        when(suggestionService.findAllVisible()).thenReturn(List.of(
                SuggestionBulkResponse.builder().text("хлеб").textDisplay("Хлеб").freq(5).build()
        ));

        String etag = mockMvc.perform(get("/api/suggestions/all"))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        mockMvc.perform(get("/api/suggestions/all").header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, etag))
                .andExpect(content().string("")); // 304 без тела
    }

    @Test
    void all_WeakenedETagByProxy_StillReturns304() throws Exception {
        // nginx при gzip ослабляет strong-ETag до weak ("h" → W/"h"). Мы и так отдаём weak,
        // но сравнение обязано матчить независимо от W/-префикса: клиент мог вернуть и strong-форму.
        when(suggestionService.findAllVisible()).thenReturn(List.of(
                SuggestionBulkResponse.builder().text("хлеб").textDisplay("Хлеб").freq(5).build()
        ));

        String weakEtag = mockMvc.perform(get("/api/suggestions/all"))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        // снимаем W/ → strong-форма того же тега
        String strongForm = weakEtag.startsWith("W/") ? weakEtag.substring(2) : weakEtag;

        mockMvc.perform(get("/api/suggestions/all").header(HttpHeaders.IF_NONE_MATCH, strongForm))
                .andExpect(status().isNotModified());
    }

    @Test
    void all_ChangedDictionary_ReturnsNewETagAnd200() throws Exception {
        when(suggestionService.findAllVisible()).thenReturn(List.of(
                SuggestionBulkResponse.builder().text("хлеб").textDisplay("Хлеб").freq(5).build()
        ));
        String oldEtag = mockMvc.perform(get("/api/suggestions/all"))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        // Словарь изменился → старый ETag не должен дать 304, тело отдаётся, ETag другой.
        when(suggestionService.findAllVisible()).thenReturn(List.of(
                SuggestionBulkResponse.builder().text("хлеб").textDisplay("Хлеб").freq(5).build(),
                SuggestionBulkResponse.builder().text("масло").textDisplay("Масло").freq(4).build()
        ));

        String newEtag = mockMvc.perform(get("/api/suggestions/all").header(HttpHeaders.IF_NONE_MATCH, oldEtag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        org.assertj.core.api.Assertions.assertThat(newEtag).isNotEqualTo(oldEtag);
    }
}
