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
import ru.mngerasimenko.todolist.dto.validation.EmailValidation;

import java.time.LocalDateTime;


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
    @Size(max = EmailValidation.MAX_LENGTH, message = EmailValidation.MAX_LENGTH_MESSAGE)
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
     * Язык писем пользователя в формате BCP-47 (e.g. "ru", "en").
     * Устанавливается при регистрации, меняется через PATCH /api/users/me/email-locale.
     */
    @JsonProperty("preferred_email_locale")
    @Size(max = 8)
    private String preferredEmailLocale;

    /**
     * Sort-настройки пользователя (Task 5). См. PATCH /api/users/me/sort-preferences.
     */
    @JsonProperty("lists_sort_mode")
    private String listsSortMode;

    @JsonProperty("lists_sort_direction")
    private String listsSortDirection;

    @JsonProperty("todos_sort_mode")
    private String todosSortMode;

    @JsonProperty("todos_sort_direction")
    private String todosSortDirection;
}
