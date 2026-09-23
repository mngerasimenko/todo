package ru.mngerasimenko.todolist.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO запроса установки нового пароля (шаг 2 — по токену из ссылки).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResetPasswordRequest {

    @NotBlank(message = "{validation.token.required}")
    private String token;

    @NotBlank(message = "{validation.password.required}")
    @Size(min = 5, max = 128, message = "{validation.password.size}")
    private String password;
}
