package ru.mngerasimenko.todolist.dto.list;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO запроса создания списка задач.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateListRequest {

    @NotBlank(message = "Название списка обязательно")
    @Size(min = 2, max = 128, message = "Название списка должно быть от 2 до 128 символов")
    private String name;

    @NotBlank(message = "Пароль списка обязателен")
    @Size(min = 3, max = 128, message = "Пароль списка должен быть от 3 до 128 символов")
    private String password;
}
