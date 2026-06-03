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

    @NotBlank(message = "List name is required")
    @Size(min = 1, max = 128, message = "List name must be between 1 and 128 characters")
    @Pattern(regexp = "^[^<>]*$", message = "Name contains invalid characters")
    private String name;
}
