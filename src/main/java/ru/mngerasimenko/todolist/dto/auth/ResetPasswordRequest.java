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

    @NotBlank(message = "Token is required")
    private String token;

    @NotBlank(message = "Password is required")
    @Size(min = 5, max = 128, message = "Password must be between 5 and 128 characters")
    private String password;
}
