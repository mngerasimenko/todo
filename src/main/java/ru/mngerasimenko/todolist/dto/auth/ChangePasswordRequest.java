package ru.mngerasimenko.todolist.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO смены пароля в сессии (зная текущий пароль).
 * Используется в {@code POST /api/auth/change-password}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChangePasswordRequest {

    @NotBlank(message = "{validation.current-password.required}")
    private String currentPassword;

    @NotBlank(message = "{validation.new-password.required}")
    @Size(min = 5, max = 128, message = "{validation.password.size}")
    private String newPassword;
}
