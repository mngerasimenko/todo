package ru.mngerasimenko.todolist.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserResponse {

    private Long id;

    @JsonProperty("auth_id")
    private String authId;

    private String email;

    private String name;

    @JsonProperty("created_task_color")
    private String createdTaskColor;

    @JsonProperty("completed_task_color")
    private String completedTaskColor;
}
