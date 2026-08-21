package ru.mngerasimenko.todolist.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import ru.mngerasimenko.todolist.model.ReminderScope;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Внутренний DTO задачи для передачи между слоями.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TodoDto {

    private Long id;

    public TodoDto(Long userId) {
        this.userId = userId;
    }

    @NotBlank(message = "Todo name is required")
    @Size(min = 1, max = 120, message = "Todo name must be between 1 and 120 characters")
    private String name;

    @JsonProperty("created_at")
    @NotNull(message = "Created at is required")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonProperty("completed_at")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime completedAt;

    @NotNull(message = "Done status is required")
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

    @JsonProperty("user_email")
    private String userEmail;

    @JsonProperty("completor_user_id")
    private Long completorUserId;

    @JsonProperty("completor_user_name")
    private String completorUserName;

    @JsonProperty("list_id")
    private Long listId;

    /**
     * Цвет иконки создателя задачи.
     */
    @JsonProperty("creator_color")
    private String creatorColor;

    /**
     * Цвет иконки исполнителя задачи.
     */
    @JsonProperty("completor_color")
    private String completorColor;

    /**
     * Позиция задачи в списке (общая per-список).
     */
    private Integer position;

    @JsonProperty("due_date")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate dueDate;

    @JsonProperty("due_time")
    @JsonFormat(pattern = "HH:mm")
    private LocalTime dueTime;

    @JsonProperty("due_timezone")
    private String dueTimezone;

    @JsonProperty("remind_before_minutes")
    private Integer remindBeforeMinutes;

    @JsonProperty("reminder_scope")
    private ReminderScope reminderScope;

    /**
     * true, если запрос явно нёс хотя бы один due-ключ (due_date/due_time/due_timezone/
     * remind_before_minutes/reminder_scope) — в отличие от того, что все они оказались null,
     * потому что клиент их вообще не прислал. Заполняется маппером из {@link TodoRequest#isDueFieldsProvided()}.
     * Не часть JSON-контракта.
     */
    @JsonIgnore
    private boolean dueFieldsProvided;

    /**
     * Возвращает значение done с null-safety (false при null).
     */
    @JsonIgnore
    public boolean isDone() {
        return done != null && done;
    }

}
