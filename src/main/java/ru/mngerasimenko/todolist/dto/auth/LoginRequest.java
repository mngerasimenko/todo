package ru.mngerasimenko.todolist.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import ru.mngerasimenko.todolist.dto.validation.EmailValidation;

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
    @NotBlank(message = "{validation.email.required}")
    @Email(message = "{validation.email.format}")
    @Size(max = EmailValidation.MAX_LENGTH, message = EmailValidation.MAX_LENGTH_MESSAGE)
    private String email;

    public void setEmail(String email) {
        this.email = email != null ? email.trim().toLowerCase() : null;
    }

    /**
     * Пароль
     */
    @NotBlank(message = "{validation.password.required}")
    @Size(min = 5, max = 128, message = "{validation.password.size}")
    private String password;
}
