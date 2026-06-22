package ru.mngerasimenko.todolist.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.mngerasimenko.todolist.dto.SuggestionResponse;
import ru.mngerasimenko.todolist.service.SuggestionService;
import ru.mngerasimenko.todolist.settings.SuggestionProperties;

import java.util.List;

/**
 * Публичный эндпоинт глобального словаря подсказок (Server R-6).
 * <p>
 * Без JWT: гостевые клиенты тоже зовут для подсказок при вводе задач —
 * это устраняет «холодный старт» для нового пользователя.
 * Из {@code permitAll} в {@link ru.mngerasimenko.todolist.security.ApiSecurityConfig}.
 */
@RestController
@RequestMapping("/api/suggestions")
@RequiredArgsConstructor
@Tag(name = "Suggestions", description = "Глобальный словарь подсказок при вводе задачи")
@Validated
public class SuggestionController {

    private final SuggestionService suggestionService;
    private final SuggestionProperties properties;

    @GetMapping
    @Operation(summary = "Топ-N подсказок задач по prefix",
            description = "Возвращает наиболее частотные строки задач, начинающиеся с указанного префикса. " +
                    "Публичный (без JWT). При префиксе короче min-prefix-length возвращает пустой список.")
    public ResponseEntity<List<SuggestionResponse>> suggest(
            @Parameter(description = "Префикс задачи (строка, как ввёл пользователь)")
            @RequestParam(name = "prefix", required = false, defaultValue = "") String prefix,
            @Parameter(description = "Сколько подсказок вернуть (1..max-limit)")
            @RequestParam(name = "limit", required = false) @Min(1) @Max(50) Integer limit
    ) {
        int effectiveLimit = limit != null ? limit : properties.getDefaultLimit();
        return ResponseEntity.ok(suggestionService.suggest(prefix, effectiveLimit));
    }
}
