package ru.mngerasimenko.todolist.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO смены отображаемого имени пользователя.
 * Используется в {@code PATCH /api/users/me/name}.
 * Ограничения — те же, что у имени при регистрации (RegisterRequest.name).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChangeNameRequest {

    @NotBlank(message = "{validation.name.required}")
    @Size(min = 2, max = 128, message = "{validation.name.size}")
    @Pattern(regexp = "^[^<>]*$", message = "{validation.name.invalid-characters}")
    private String name;
}
