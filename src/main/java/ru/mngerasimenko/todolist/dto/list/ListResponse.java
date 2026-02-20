package ru.mngerasimenko.todolist.dto.list;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Ответ с информацией о списке задач.
 * Поле role — роль текущего пользователя в данном списке (ADMIN/USER).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ListResponse {

    private Long id;

    private String name;

    /**
     * Роль текущего пользователя в списке.
     */
    private String role;

    @JsonProperty("created_at")
    private String createdAt;
}
