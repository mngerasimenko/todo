package ru.mngerasimenko.todolist.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO ответа пользователя для клиента.
 */
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

    @JsonProperty("email_verified")
    private Boolean emailVerified;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @JsonProperty("subscription_type")
    private String subscriptionType;

    @JsonProperty("subscription_expires_at")
    private LocalDateTime subscriptionExpiresAt;

    @JsonProperty("is_beta_tester")
    private Boolean betaTester;

    /**
     * Язык писем пользователя (BCP-47, e.g. "ru", "en"). Возвращается в /api/users/me
     * и в LoginResponse — клиенты используют для отображения текущей настройки в Settings.
     */
    @JsonProperty("preferred_email_locale")
    private String preferredEmailLocale;

    /**
     * Режим сортировки списков задач: MANUAL | ALPHABETICAL | CREATED_AT.
     * Управляется PATCH /api/users/me/sort-preferences.
     */
    @JsonProperty("lists_sort_mode")
    private String listsSortMode;

    /**
     * Направление сортировки списков: ASC | DESC.
     */
    @JsonProperty("lists_sort_direction")
    private String listsSortDirection;

    /**
     * Режим сортировки задач внутри списка: MANUAL | ALPHABETICAL | CREATED_AT.
     */
    @JsonProperty("todos_sort_mode")
    private String todosSortMode;

    /**
     * Направление сортировки задач: ASC | DESC.
     */
    @JsonProperty("todos_sort_direction")
    private String todosSortDirection;
}
