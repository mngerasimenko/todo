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

    @NotBlank(message = "Current password is required")
    private String currentPassword;

    @NotBlank(message = "Password is required")
    @Size(min = 5, max = 128, message = "Password must be between 5 and 128 characters")
    private String newPassword;
}
