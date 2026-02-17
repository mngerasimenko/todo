package ru.mngerasimenko.todolist.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO для запроса регистрации пользователя
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterRequest {

    /**
     * Email пользователя
     */
    @NotBlank(message = "Email не может быть пустым")
    @Email(message = "Некорректный формат email")
    @Size(max = 128, message = "Email не должен превышать 128 символов")
    private String email;

    /**
     * Имя пользователя
     */
    @NotBlank(message = "Имя пользователя не может быть пустым")
    @Size(min = 2, max = 128, message = "Имя пользователя должно содержать от 2 до 128 символов")
    private String name;

    /**
     * Пароль
     */
    @NotBlank(message = "Пароль не может быть пустым")
    @Size(min = 5, max = 128, message = "Пароль должен содержать от 5 до 128 символов")
    private String password;
}
