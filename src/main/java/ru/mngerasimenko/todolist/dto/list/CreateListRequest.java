package ru.mngerasimenko.todolist.dto.list;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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

    @NotBlank(message = "{validation.list-name.required}")
    @Size(min = 1, max = 128, message = "{validation.list-name.size}")
    @Pattern(regexp = "^[^<>]*$", message = "{validation.list-name.invalid-characters}")
    private String name;
}
