package ru.mngerasimenko.todolist.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO запроса обновления цветов задач пользователя.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateColorsRequest {

    @JsonProperty("created_task_color")
    @NotBlank(message = "{validation.color.created-task.required}")
    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "{validation.color.format}")
    private String createdTaskColor;

    @JsonProperty("completed_task_color")
    @NotBlank(message = "{validation.color.completed-task.required}")
    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "{validation.color.format}")
    private String completedTaskColor;
}
