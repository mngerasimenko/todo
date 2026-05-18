package ru.mngerasimenko.todolist.dto.list;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

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
     * Цвет списка в формате #RRGGBB (опционально, может быть null).
     */
    private String color;

    /**
     * Имя создателя списка.
     */
    @JsonProperty("creator_name")
    private String creatorName;

    /**
     * Роль текущего пользователя в списке.
     */
    private String role;

    @JsonProperty("created_at")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;
}
