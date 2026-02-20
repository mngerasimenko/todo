package ru.mngerasimenko.todolist.dto.list;

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
public class JoinListRequest {

    @NotBlank(message = "Название списка обязательно")
    @Size(min = 2, max = 128)
    private String name;

    @NotBlank(message = "Пароль списка обязателен")
    @Size(min = 3, max = 128)
    private String password;
}
