package ru.mngerasimenko.todolist.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


/**
 * Внутренний DTO пользователя для передачи между слоями.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDto {
    private Long id;

    @JsonProperty("auth_id")
    @NotBlank
    @Size(max = 128)
    private String authId;

    @Email
    @NotBlank
    @Size(max = 128)
    private String email;

    @JsonIgnore
    @NotBlank
    @Size(min = 5, max = 128)
    private String password;

    @NotBlank
    private String name;

    @JsonProperty("created_task_color")
    private String createdTaskColor;

    @JsonProperty("completed_task_color")
    private String completedTaskColor;
}
