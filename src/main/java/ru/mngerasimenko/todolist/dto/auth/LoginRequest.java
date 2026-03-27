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
    @NotBlank(message = "Email не может быть пустым")
    @Email(message = "Некорректный формат email")
    @Size(max = 128, message = "Email не должен превышать 128 символов")
    private String email;

    /**
     * Пароль
     */
    @NotBlank(message = "Пароль не может быть пустым")
    @Size(min = 5, max = 128, message = "Пароль должен содержать от 5 до 128 символов")
    private String password;
}
