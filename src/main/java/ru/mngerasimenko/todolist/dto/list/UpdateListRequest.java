package ru.mngerasimenko.todolist.dto.list;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO запроса PATCH /api/lists/{id} — переименование списка (ADMIN-only).
 * {@code name} опционален: {@code null} — имя не изменяется.
 * Цвет здесь больше не задаётся — он per-user, см. PATCH /api/lists/{id}/personalization.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateListRequest {

    @Size(min = 1, max = 128, message = "{validation.list-name.size}")
    @Pattern(regexp = "^[^<>]*$", message = "{validation.list-name.invalid-characters}")
    private String name;
}
