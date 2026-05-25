package ru.mngerasimenko.todolist.dto.list;

import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO запроса PATCH /api/lists/{id}/personalization — персональные (per-user) настройки списка.
 * Сейчас — только цвет ({@code #RRGGBB} или {@code null} для сброса). Доступно любому участнику.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PersonalizationRequest {

    @Pattern(
            regexp = "^#[0-9a-fA-F]{6}$",
            message = "Color must be in #RRGGBB format"
    )
    private String color;
}
