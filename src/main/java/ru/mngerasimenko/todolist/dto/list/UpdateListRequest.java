package ru.mngerasimenko.todolist.dto.list;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO запроса PATCH /api/lists/{id} — частичное обновление списка (ADMIN-only).
 * Оба поля опциональные: можно поменять только name, только color, или оба сразу.
 * Если поле {@code null} — оно не изменяется.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateListRequest {

    @Size(min = 2, max = 128, message = "Name must be between 2 and 128 characters")
    @Pattern(regexp = "^[^<>]*$", message = "Name contains invalid characters")
    private String name;

    @Pattern(
            regexp = "^#[0-9a-fA-F]{6}$",
            message = "Color must be in #RRGGBB format"
    )
    private String color;
}
