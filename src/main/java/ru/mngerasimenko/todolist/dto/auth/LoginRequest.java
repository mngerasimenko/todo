package ru.mngerasimenko.todolist.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO для запроса входа пользователя
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginRequest {

    /**
     * Email пользователя
     */
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    @Size(max = 128, message = "Email must not exceed 128 characters")
    private String email;

    public void setEmail(String email) {
        this.email = email != null ? email.trim().toLowerCase() : null;
    }

    /**
     * Пароль
     */
    @NotBlank(message = "Password is required")
    @Size(min = 5, max = 128, message = "Password must be between 5 and 128 characters")
    private String password;
}
