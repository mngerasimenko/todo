package ru.mngerasimenko.todolist.dto.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateAccountRequest {

    @NotBlank(message = "Название аккаунта обязательно")
    @Size(min = 2, max = 128, message = "Название аккаунта должно быть от 2 до 128 символов")
    private String name;

    @NotBlank(message = "Пароль аккаунта обязателен")
    @Size(min = 3, max = 128, message = "Пароль аккаунта должен быть от 3 до 128 символов")
    private String password;
}
