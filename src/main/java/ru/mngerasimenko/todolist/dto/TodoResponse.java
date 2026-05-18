package ru.mngerasimenko.todolist.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO ответа задачи для клиента.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TodoResponse {

    private Long id;

    private String name;

    @JsonProperty("created_at")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonProperty("completed_at")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime completedAt;

    @JsonProperty("done")
    private Boolean done;

    @JsonProperty("is_private")
    private Boolean isPrivate;

    /**
     * Возвращает значение isPrivate с null-safety (false при null).
     */
    @JsonIgnore
    public boolean isPrivate() {
        return isPrivate != null && isPrivate;
    }

    @JsonProperty("user_id")
    private Long userId;

    @JsonProperty("user_name")
    private String userName;

    @JsonProperty("completor_user_id")
    private Long completorUserId;

    @JsonProperty("completor_user_name")
    private String completorUserName;

    @JsonProperty("list_id")
    private Long listId;

    @JsonProperty("creator_color")
    private String creatorColor;

    @JsonProperty("completor_color")
    private String completorColor;

    /**
     * Позиция задачи в списке (общая per-список). Используется клиентом
     * для отображения порядка после bulk-reorder (PATCH /api/lists/{id}/todos/reorder).
     */
    private Integer position;

    /**
     * Возвращает значение done с null-safety (false при null).
     * Используется в Java-коде, не для JSON-сериализации.
     */
    @JsonIgnore
    public boolean isDone() {
        return done != null && done;
    }
}
