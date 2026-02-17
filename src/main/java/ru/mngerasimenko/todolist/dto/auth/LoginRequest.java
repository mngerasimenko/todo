package ru.mngerasimenko.todolist.dto.auth;

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
     * Имя пользователя
     */
    @NotBlank(message = "Имя пользователя не может быть пустым")
    @Size(min = 2, max = 128, message = "Имя пользователя должно содержать от 2 до 128 символов")
    private String username;

    /**
     * Пароль
     */
    @NotBlank(message = "Пароль не может быть пустым")
    @Size(min = 5, max = 128, message = "Пароль должен содержать от 5 до 128 символов")
    private String password;
}
